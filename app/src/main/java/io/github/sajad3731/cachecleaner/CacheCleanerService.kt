package io.github.sajad3731.cachecleaner

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Clears other apps' caches the only way Android leaves open to a normal app: it opens each
 * app's Storage page in Settings and presses Clear cache, as the user would.
 *
 * It acts only while a cleaning the user started is running, and only on Settings screens.
 * A full-screen overlay covers Settings meanwhile, shows progress, and offers Stop.
 *
 * Each step reacts to Settings' window events instead of waiting fixed delays, with a short poll
 * as a fallback. Only a button proven to be Clear cache (by a known id or Settings' own label,
 * and with no Clear data / Clear storage text in it) is ever pressed.
 */
class CacheCleanerService : AccessibilityService() {

    private enum class Step {
        IDLE,
        /** The app's App info page: open its Storage row. */
        APP_INFO,
        /** The app's Storage page: press Clear cache. */
        STORAGE,
        /** Xiaomi: the menu that opens from App info's bottom button, holding Clear cache. */
        CHOOSER,
        /**
         * Clear cache pressed. Settings clears in the background once pressed, so this only
         * waits a moment for a "Clear cache?" dialog to confirm, then moves on.
         */
        CLEARING,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<CleanTarget>()
    private val retryQueue = ArrayList<CleanTarget>()
    private val retried = HashSet<String>()
    private val failedLabels = ArrayList<String>()
    private var current: CleanTarget? = null
    private var step = Step.IDLE
    private var stepStartedAt = 0L
    private var appStartedAt = 0L
    private var lastActionAt = 0L

    /** False from a click that opens a new screen until that screen's window appears. */
    private var windowReady = true
    private var scrolls = 0
    private var total = 0
    private var processed = 0
    private var cleaned = 0
    private var debuggable = false

    private var settingsPackage = DEFAULT_SETTINGS_PACKAGE
    private var watchedPackages: Set<String> = setOf(DEFAULT_SETTINGS_PACKAGE)
    private var storageLabels: Set<String> = emptySet()
    private var clearCacheLabels: Set<String> = emptySet()
    private var clearDataLabels: Set<String> = emptySet()

    private var overlay: View? = null

    /** The screen went off mid-run: the current app starts over once the phone is unlocked. */
    private var paused = false
    private var pausedAt = 0L
    private var runStartedAt = 0L

    /**
     * Set once an app in this run cleared with no "Clear cache?" dialog: this Settings asks for
     * no confirmation, so later apps move on right after the press.
     */
    private var noConfirmDialog = false
    private var useDirectStorage = false
    private var startedDirect = false
    private var directFailures = 0

    val isRunning: Boolean get() = step != Step.IDLE

    override fun onServiceConnected() {
        instance = this
        debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Turned off, or suspended by another automation, mid-run: leave Settings for the app.
        finish(stopped = true)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing is read unless a cleaning is running; then only Settings screens matter.
        if (!isRunning || paused || event == null || event.packageName?.toString() !in watchedPackages) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) windowReady = true
        scheduleTick(EVENT_DEBOUNCE_MS)
    }

    fun clean(targets: List<CleanTarget>) {
        if (isRunning || targets.isEmpty()) return
        resolveSettingsLabels()
        queue.clear()
        queue.addAll(targets)
        retryQueue.clear()
        retried.clear()
        failedLabels.clear()
        total = targets.size
        processed = 0
        cleaned = 0
        paused = false
        noConfirmDialog = false
        runStartedAt = SystemClock.uptimeMillis()
        showOverlay()
        // The overlay keeps the screen on, but the user may still lock the phone. Settings
        // cannot be worked behind the lock screen, so the run waits for the unlock.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenState, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        next()
    }

    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_USER_PRESENT -> resume()
                // Without a lock screen, no USER_PRESENT may follow.
                Intent.ACTION_SCREEN_ON ->
                    if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) resume()
            }
        }
    }

    private fun pause() {
        if (!isRunning || paused) return
        paused = true
        pausedAt = SystemClock.uptimeMillis()
        handler.removeCallbacksAndMessages(null)
        // The app being cleaned starts over after the unlock, without using up its retry.
        current?.let { queue.addFirst(it) }
        current = null
        // The overlay would sit over the lock screen and block unlocking.
        hideOverlay()
    }

    private fun resume() {
        if (!isRunning || !paused) return
        paused = false
        // Long after the lock, Settings suddenly taking over the screen would surprise the user.
        if (SystemClock.uptimeMillis() - pausedAt > MAX_PAUSE_MS) {
            finish(stopped = true)
            return
        }
        showOverlay()
        next()
    }

    private fun next() {
        handler.removeCallbacksAndMessages(null)
        if (queue.isEmpty() && retryQueue.isNotEmpty()) {
            // Apps that failed once get one more try at the end, when Settings has settled.
            queue.addAll(retryQueue)
            retryQueue.clear()
        }
        val target = queue.removeFirstOrNull()
        if (target == null) {
            finish(stopped = false)
            return
        }
        current = target
        appStartedAt = SystemClock.uptimeMillis()
        updateOverlay(target)
        // Straight to the Storage page where Settings offers it (Samsung, AOSP 11+); a second
        // try goes through App info, in case the direct page did not work for this app.
        val direct = useDirectStorage && target.packageName !in retried
        val intent = Intent(
            if (direct) ACTION_APP_STORAGE_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", target.packageName, null)
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        if (direct) intent.setPackage(DEFAULT_SETTINGS_PACKAGE)
        try {
            startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            // The direct Storage page did not open: the second try goes through App info.
            fail(target, retry = direct)
            return
        } catch (ignored: SecurityException) {
            useDirectStorage = false
            fail(target, retry = true)
            return
        }
        enterStep(if (direct) Step.STORAGE else Step.APP_INFO, expectNewWindow = true)
        startedDirect = direct
        scheduleTick(POLL_MS)
    }

    /** Whether Settings has an exported Storage page that opens for one app by package. */
    private fun directStorageAvailable(): Boolean {
        if (isXiaomi) return false
        val probe = Intent(ACTION_APP_STORAGE_SETTINGS, Uri.fromParts("package", packageName, null))
            .setPackage(DEFAULT_SETTINGS_PACKAGE)
        val info = packageManager.resolveActivity(probe, 0)?.activityInfo ?: return false
        return info.exported && info.permission == null
    }

    private fun enterStep(newStep: Step, expectNewWindow: Boolean) {
        current?.let { log(it, "step $newStep") }
        step = newStep
        stepStartedAt = SystemClock.uptimeMillis()
        lastActionAt = stepStartedAt
        scrolls = 0
        windowReady = !expectNewWindow
    }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delay)
    }

    private val tick = Runnable { advance() }

    private fun advance() {
        val target = current ?: return
        val now = SystemClock.uptimeMillis()
        if (now - stepStartedAt > STEP_TIMEOUT_MS) {
            fail(target, retry = true)
            return
        }
        // Some screens open without a window event; after a moment act on what is there.
        if (!windowReady && now - stepStartedAt >= WINDOW_FALLBACK_MS) windowReady = true
        val root = settingsRoot()
        // A header naming this app proves the new page is up; no need to wait for the event.
        if (!windowReady && root != null && headerMatches(root, target)) windowReady = true
        if (root != null && windowReady) {
            when (step) {
                Step.APP_INFO -> onAppInfo(root, target)
                Step.STORAGE -> onStorage(root, target)
                Step.CHOOSER -> onChooser(root)
                Step.CLEARING -> onClearing(root, target)
                Step.IDLE -> return
            }
        }
        if (isRunning && current === target) scheduleTick(POLL_MS)
    }

    private fun onAppInfo(root: AccessibilityNodeInfo, target: CleanTarget) {
        if (!showsApp(root, target)) return
        // A few Settings versions put Clear cache right on App info.
        if (findClearCacheButton(root) != null) {
            onStorage(root, target)
            return
        }
        // Xiaomi first: its App info has a Storage row too, but that page has no Clear cache.
        if (isXiaomi) {
            xiaomiClearMenuButton(root)?.let { button ->
                if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) enterStep(Step.CHOOSER, expectNewWindow = false)
                return
            }
        }
        storageRow(root)?.let { row ->
            if (row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) enterStep(Step.STORAGE, expectNewWindow = true)
            return
        }
        // The Storage row sits below the fold on many devices.
        if (scrolls < MAX_SCROLLS && findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
            scrolls++
        }
    }

    private fun onStorage(root: AccessibilityNodeInfo, target: CleanTarget) {
        if (!showsApp(root, target)) return
        val button = findClearCacheButton(root) ?: return
        if (!button.isEnabled) {
            // Grey from the start: either the sizes are still loading or the cache is empty.
            if (SystemClock.uptimeMillis() - stepStartedAt >= LOAD_WAIT_MS) done(target, "already empty")
            return
        }
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        if (noConfirmDialog) done(target, "pressed") else enterStep(Step.CLEARING, expectNewWindow = false)
    }

    private fun onChooser(root: AccessibilityNodeInfo) {
        val item = findByExactText(root, clearCacheLabels)
            .mapNotNull(::clickableAncestor)
            .firstOrNull(::isSafeToPress) ?: return
        if (item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) enterStep(Step.CLEARING, expectNewWindow = false)
    }

    private fun onClearing(root: AccessibilityNodeInfo, target: CleanTarget) {
        val now = SystemClock.uptimeMillis()
        confirmButton(root)?.let { ok ->
            if (ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) done(target, "confirmed")
            return
        }
        // Pressing Clear cache hands the job to Android, which finishes it even after Settings
        // is replaced by the next app. Only a confirmation dialog is worth a short wait.
        val button = findClearCacheButton(root)
        when {
            button != null && !button.isEnabled -> {
                noConfirmDialog = true
                done(target, "button grey")
            }
            now - lastActionAt >= DIALOG_WAIT_MS -> done(target, "pressed")
        }
    }

    private fun done(target: CleanTarget, why: String) {
        cleaned++
        processed++
        log(target, "cleaned ($why)")
        next()
    }

    private fun fail(target: CleanTarget, retry: Boolean) {
        // The direct Storage page never showed Clear cache: use App info for the rest.
        if (startedDirect && step == Step.STORAGE && ++directFailures >= MAX_DIRECT_FAILURES && cleaned == 0) {
            useDirectStorage = false
        }
        if (retry && retried.add(target.packageName)) {
            log(target, "retry later (step $step)")
            retryQueue.add(target)
        } else {
            log(target, "skipped (step $step)")
            failedLabels.add(target.label)
            processed++
        }
        next()
    }

    private fun log(target: CleanTarget, what: String) {
        if (debuggable) Log.d(TAG, "${target.packageName}: $what in ${SystemClock.uptimeMillis() - appStartedAt} ms")
    }

    /**
     * Settings shows the app's name in a header on both pages. Checking it keeps a page left
     * over from the previous app from being acted on. Pages without that header pass.
     */
    private fun showsApp(root: AccessibilityNodeInfo, target: CleanTarget): Boolean {
        val header = header(root) ?: return true
        return sameLabel(header.text, target.label)
    }

    private fun headerMatches(root: AccessibilityNodeInfo, target: CleanTarget): Boolean =
        header(root)?.let { sameLabel(it.text, target.label) } == true

    /** Labels compared without direction marks and odd spaces, which RTL Settings may add. */
    private fun sameLabel(shown: CharSequence?, label: String): Boolean =
        shown != null && normalizeLabel(shown) == normalizeLabel(label)

    private fun normalizeLabel(text: CharSequence): String =
        text.toString().replace(BIDI_MARKS, "").replace(SPACES, " ").trim()

    private fun header(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        HEADER_IDS.asSequence()
            .flatMap { id -> watchedPackages.asSequence().map { "$it:id/$id" } }
            .mapNotNull { root.findAccessibilityNodeInfosByViewId(it).firstOrNull() }
            .firstOrNull()

    /** The Storage row on App info. The Storage page's own title reads "Storage" too, so only a clickable row counts. */
    private fun storageRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val matches = findByExactText(root, storageLabels)
        val preferred = matches.filter { it.viewIdResourceName == "android:id/title" } + matches
        return preferred.asSequence()
            .mapNotNull(::clickableAncestor)
            .firstOrNull { !containsAny(it, clearDataLabels) }
    }

    /**
     * Clear cache, matched by a known id or Settings' own label, never by position. Pixel and
     * AOSP name both action buttons button1/button2, so there only the label decides.
     */
    private fun findClearCacheButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (pkg in watchedPackages) {
            for (id in CACHE_BUTTON_IDS) {
                val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
                if (node != null && !containsAny(node, clearDataLabels)) return node
            }
        }
        return findByExactText(root, clearCacheLabels).asSequence()
            .filter { it.isVisibleToUser }
            .mapNotNull { text -> clickableAncestor(text) ?: text.takeIf { !it.isEnabled } }
            .firstOrNull(::isSafeToPress)
    }

    /** Only a node whose own text is Clear cache, with nothing about clearing data in it. */
    private fun isSafeToPress(node: AccessibilityNodeInfo): Boolean =
        !containsAny(node, clearDataLabels) && texts(node).any { text -> clearCacheLabels.any { it.equals(text, true) } }

    /**
     * Xiaomi (MIUI, HyperOS) has no Storage page: App info's bottom button opens a menu with
     * Clear cache and Clear all data. That button only opens the menu; in it, only Clear cache
     * is pressed.
     */
    private fun xiaomiClearMenuButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByExactText(root, xiaomiMenuLabels).asSequence()
            .filter { it.isVisibleToUser }
            .mapNotNull(::clickableAncestor)
            .firstOrNull()

    private var xiaomiMenuLabels: Set<String> = emptySet()
    private val isXiaomi: Boolean get() = settingsPackage == XIAOMI_SECURITY_CENTER

    /** OK in a "Clear cache?" dialog. A dialog that mentions clearing data is never confirmed. */
    private fun confirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ok = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull { it.isVisibleToUser }
            ?: return null
        // Only the dialog's own text counts: the smallest part around OK that names clearing
        // cache, not the Storage page that may sit behind it in the same window.
        var node: AccessibilityNodeInfo? = ok.parent
        var levels = 0
        while (node != null && levels++ < MAX_DIALOG_LEVELS) {
            val all = texts(node, maxDepth = DIALOG_TEXT_DEPTH)
            if (all.any { text -> clearCacheLabels.any { text.contains(it, true) } }) {
                val mentionsData = all.any { text -> clearDataLabels.any { text.contains(it, true) } }
                return if (mentionsData) null else ok
            }
            node = node.parent
        }
        return null
    }

    private fun containsAny(node: AccessibilityNodeInfo, labels: Set<String>): Boolean =
        texts(node).any { text -> labels.any { text.contains(it, true) } }

    /** Text and descriptions of a node and its children, a few levels deep. */
    private fun texts(node: AccessibilityNodeInfo, maxDepth: Int = TEXT_DEPTH, depth: Int = 0): List<String> {
        val own = listOfNotNull(node.text?.toString()?.trim(), node.contentDescription?.toString()?.trim())
            .filter { it.isNotEmpty() }
        if (depth >= maxDepth) return own
        return own + (0 until node.childCount).flatMap { i ->
            node.getChild(i)?.let { texts(it, maxDepth, depth + 1) }.orEmpty()
        }
    }

    private fun settingsRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() in watchedPackages) return it }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() in watchedPackages }
    }

    private fun findByExactText(root: AccessibilityNodeInfo, labels: Set<String>): List<AccessibilityNodeInfo> =
        labels.flatMap { label ->
            root.findAccessibilityNodeInfosByText(label).filter { it.text?.toString()?.trim().equals(label, true) }
        }

    /** The node itself or a near ancestor that takes clicks; far ancestors are whole lists. */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        var levels = 0
        while (candidate != null && !candidate.isClickable) {
            if (++levels > MAX_ANCESTOR_LEVELS) return null
            candidate = candidate.parent
        }
        return candidate
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
        }
        return null
    }

    /**
     * Reads Settings' own labels in the current language from the package that shows App info
     * (Settings, or Xiaomi's Security app), plus known OEM names and fallbacks.
     */
    private fun resolveSettingsLabels() {
        val probe = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        settingsPackage = packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
            ?: DEFAULT_SETTINGS_PACKAGE
        watchedPackages = setOf(settingsPackage, DEFAULT_SETTINGS_PACKAGE)
        fun strings(names: List<String>): List<String> = watchedPackages.flatMap { pkg ->
            val resources = try {
                packageManager.getResourcesForApplication(pkg)
            } catch (ignored: PackageManager.NameNotFoundException) {
                return@flatMap emptyList()
            }
            names.mapNotNull { name ->
                // Settings' own resource names, looked up by name on purpose.
                @Suppress("DiscouragedApi")
                val id = resources.getIdentifier(name, "string", pkg)
                if (id == 0) null else runCatching { resources.getString(id).trim() }.getOrNull()
            }
        }.filter { it.isNotEmpty() && !it.contains('%') }
        storageLabels = (strings(STORAGE_NAMES) + FALLBACK_STORAGE_LABELS).toSet()
        clearCacheLabels = (strings(CLEAR_CACHE_NAMES) + FALLBACK_CLEAR_CACHE_LABELS).toSet()
        clearDataLabels = (strings(CLEAR_DATA_NAMES) + FALLBACK_CLEAR_DATA_LABELS).toSet() - clearCacheLabels
        xiaomiMenuLabels = (strings(XIAOMI_MENU_NAMES) + FALLBACK_XIAOMI_MENU_LABELS).toSet()
        useDirectStorage = directStorageAvailable()
        directFailures = 0
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = LayoutInflater.from(Appearance.themedContext(this))
            .inflate(R.layout.overlay_cleaning, null)
        view.findViewById<Button>(R.id.stopButton).setOnClickListener { finish(stopped = true) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so Settings stays the active window the service works on.
            // Kept on, so the phone does not lock itself halfway through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun updateOverlay(target: CleanTarget) {
        val view = overlay ?: return
        view.findViewById<TextView>(R.id.overlayProgress).text =
            getString(R.string.overlay_progress, minOf(processed + 1, total), total, target.label)
        view.findViewById<LinearProgressIndicator>(R.id.overlayBar).apply {
            max = total
            setProgressCompat(processed, true)
        }
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    private fun finish(stopped: Boolean) {
        if (!isRunning) return
        handler.removeCallbacksAndMessages(null)
        step = Step.IDLE
        // Apps waiting for their second try count as skipped.
        failedLabels.addAll(retryQueue.map { it.label })
        // Stopped early: apps already tried once count as skipped, the rest were not reached.
        var notReached = 0
        (listOfNotNull(current) + queue).forEach { target ->
            if (target.packageName in retried) failedLabels.add(target.label) else notReached++
        }
        current = null
        queue.clear()
        retryQueue.clear()
        runCatching { unregisterReceiver(screenState) }
        paused = false
        hideOverlay()
        if (debuggable) {
            Log.d(TAG, "run: $cleaned cleaned of $total in ${SystemClock.uptimeMillis() - runStartedAt} ms")
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_CLEANED, cleaned)
                .putStringArrayListExtra(EXTRA_FAILED_NAMES, ArrayList(failedLabels))
                .putExtra(EXTRA_STOPPED, stopped)
                .putExtra(EXTRA_NOT_REACHED, notReached)
        )
    }

    companion object {
        const val EXTRA_CLEANED = "cleaned"
        const val EXTRA_FAILED_NAMES = "failed_names"
        const val EXTRA_STOPPED = "stopped"
        const val EXTRA_NOT_REACHED = "not_reached"

        private const val TAG = "CacheCleaner"
        private const val DEFAULT_SETTINGS_PACKAGE = "com.android.settings"
        private const val XIAOMI_SECURITY_CENTER = "com.miui.securitycenter"

        /** Settings' own action for one app's Storage page (Samsung, AOSP 11+); not in the SDK. */
        private const val ACTION_APP_STORAGE_SETTINGS = "com.android.settings.APP_STORAGE_SETTINGS"
        private const val MAX_DIRECT_FAILURES = 3

        /** App name headers: AOSP, Pixel, Samsung, ColorOS and Vivo; Xiaomi's Security app. */
        private val HEADER_IDS = listOf("entity_header_title", "am_detail_label")

        /** Ids that only ever belong to Clear cache: Samsung, older AOSP, OEM forks. */
        private val CACHE_BUTTON_IDS = listOf("cache_button", "clear_cache_button", "clear_cache_btn")

        private val STORAGE_NAMES = listOf(
            "storage_label", "storage_settings", "storage_settings_for_app", "storage_use",
            "storage_usage", "app_storage", "storage_title", "app_info_storage_title",
        )
        private val CLEAR_CACHE_NAMES = listOf(
            "clear_cache_btn_text", "clear_cache", "clear_cache_button", "app_manager_clear_cache",
            "app_info_clear_cache",
        )
        private val CLEAR_DATA_NAMES = listOf(
            "clear_user_data_text", "clear_data", "clear_storage", "clear_storage_btn_text",
            "clear_data_btn_text", "app_manager_clear_all_data", "app_manager_clear_data",
            "clear_all_data", "clear_instant_app_data", "manage_space_text",
        )
        private val XIAOMI_MENU_NAMES = listOf("app_manager_clear_data", "app_manager_clear_data_title")

        private val FALLBACK_STORAGE_LABELS = listOf(
            "Storage", "Storage & cache", "Storage usage", "Storage and cache", "فضای ذخیره‌سازی",
            "حافظه و حافظه پنهان",
        )
        private val FALLBACK_CLEAR_CACHE_LABELS = listOf("Clear cache", "پاک کردن حافظه پنهان", "پاک کردن حافظه نهان")
        private val FALLBACK_CLEAR_DATA_LABELS = listOf(
            "Clear data", "Clear storage", "Clear all data", "Delete data", "Delete all data",
            "پاک کردن داده‌ها", "پاک کردن داده", "پاک کردن فضای ذخیره‌سازی", "پاک کردن همه داده‌ها",
        )
        private val FALLBACK_XIAOMI_MENU_LABELS = listOf("Clear data", "پاک کردن داده‌ها")

        private const val POLL_MS = 100L
        private const val EVENT_DEBOUNCE_MS = 40L
        private const val WINDOW_FALLBACK_MS = 500L
        private const val LOAD_WAIT_MS = 1_200L
        /** After Clear cache: how long a "Clear cache?" dialog may take to appear. */
        private const val DIALOG_WAIT_MS = 300L
        private const val MAX_PAUSE_MS = 10 * 60 * 1000L
        private const val STEP_TIMEOUT_MS = 4_000L
        private const val MAX_SCROLLS = 6
        private const val MAX_ANCESTOR_LEVELS = 3
        private const val TEXT_DEPTH = 4
        private const val DIALOG_TEXT_DEPTH = 16
        private const val MAX_DIALOG_LEVELS = 8

        /** Direction marks and isolates (LRM, RLM, ALM, embeddings, isolates) and zero-width marks. */
        private val BIDI_MARKS = Regex("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
        private val SPACES = Regex("[\\s\\u00A0\\u202F]+")

        /** Set while Android has the service bound, which happens only when the user enabled it. */
        @Volatile
        var instance: CacheCleanerService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val self = ComponentName(context, CacheCleanerService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }
}

data class CleanTarget(val packageName: String, val label: String)
