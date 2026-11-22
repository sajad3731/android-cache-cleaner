package io.github.sajad3731.cachecleaner

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Clears other apps' caches the only way Android leaves open to a normal app: it opens each
 * app's Storage page in Settings and presses Clear cache, as the user would.
 *
 * It acts only while a cleaning the user started is running, and only on Settings screens.
 * A full-screen overlay covers Settings meanwhile, shows progress, and offers Stop.
 */
class CacheCleanerService : AccessibilityService() {

    private enum class Step { IDLE, APP_INFO, STORAGE, CLEARED }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<CleanTarget>()
    private var current: CleanTarget? = null
    private var step = Step.IDLE
    private var stepStartedAt = 0L
    private var scrolls = 0
    private var total = 0
    private var cleaned = 0
    private var failed = 0

    private var settingsPackage = DEFAULT_SETTINGS_PACKAGE
    private var storageLabels: Set<String> = emptySet()
    private var clearCacheLabels: Set<String> = emptySet()

    private var overlay: View? = null

    val isRunning: Boolean get() = step != Step.IDLE

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Turned off, or suspended by another automation, mid-run: leave Settings for the app.
        finish(stopped = true, reopenApp = true)
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
        if (!isRunning || event?.packageName != settingsPackage) return
        scheduleTick(EVENT_DEBOUNCE_MS)
    }

    fun clean(targets: List<CleanTarget>) {
        if (isRunning || targets.isEmpty()) return
        resolveSettingsLabels()
        queue.clear()
        queue.addAll(targets)
        total = targets.size
        cleaned = 0
        failed = 0
        showOverlay()
        next()
    }

    private fun next() {
        handler.removeCallbacksAndMessages(null)
        val target = queue.removeFirstOrNull()
        if (target == null) {
            finish(stopped = false, reopenApp = true)
            return
        }
        current = target
        updateOverlay(target)
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", target.packageName, null)
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            failed++
            next()
            return
        }
        enterStep(Step.APP_INFO)
        scheduleTick(FIRST_TICK_MS)
    }

    private fun enterStep(newStep: Step) {
        step = newStep
        stepStartedAt = SystemClock.uptimeMillis()
        scrolls = 0
    }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delay)
    }

    private val tick = Runnable { advance() }

    private fun advance() {
        val target = current ?: return
        if (step == Step.CLEARED) return
        if (SystemClock.uptimeMillis() - stepStartedAt > STEP_TIMEOUT_MS) {
            failed++
            next()
            return
        }
        val root = settingsRoot()
        when {
            root == null -> Unit
            step == Step.APP_INFO -> openStorage(root, target)
            step == Step.STORAGE -> pressClearCache(root, target)
        }
        if (step == Step.APP_INFO || step == Step.STORAGE) scheduleTick(POLL_MS)
    }

    private fun openStorage(root: AccessibilityNodeInfo, target: CleanTarget) {
        if (!showsApp(root, target)) return
        // Only a preference row counts: the Storage page's own title bar reads "Storage" too.
        val row = findByExactText(root, storageLabels)
            .firstOrNull { it.viewIdResourceName == "android:id/title" }
            ?: findByExactText(root, storageLabels).firstOrNull { clickableAncestor(it) != null && it.viewIdResourceName != null }
        if (row != null) {
            if (clickableAncestor(row)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                enterStep(Step.STORAGE)
            }
            return
        }
        // The Storage row sits below the fold on most devices.
        if (scrolls < MAX_SCROLLS) {
            findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrolls++
        }
    }

    private fun pressClearCache(root: AccessibilityNodeInfo, target: CleanTarget) {
        if (!showsApp(root, target)) return
        // Match the button by its id or by Settings' own "Clear cache" text, never by
        // position, so Clear data / Clear storage can never be pressed by mistake.
        val button = root.findAccessibilityNodeInfosByViewId("$settingsPackage:id/cache_button").firstOrNull()
            ?: findByExactText(root, clearCacheLabels).firstNotNullOfOrNull(::clickableAncestor)
            ?: return
        // A disabled button means the cache is already empty.
        if (button.isEnabled) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        cleaned++
        step = Step.CLEARED
        handler.postDelayed({ next() }, AFTER_CLEAR_MS)
    }

    /**
     * Settings shows the app's name in a header on both pages. Checking it keeps a page left
     * over from the previous app from being acted on. Pages without that header pass.
     */
    private fun showsApp(root: AccessibilityNodeInfo, target: CleanTarget): Boolean {
        val header = root.findAccessibilityNodeInfosByViewId("$settingsPackage:id/entity_header_title")
            .firstOrNull() ?: return true
        return header.text?.toString()?.trim() == target.label.trim()
    }

    private fun settingsRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName == settingsPackage) return it }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName == settingsPackage }
    }

    private fun findByExactText(root: AccessibilityNodeInfo, labels: Set<String>): List<AccessibilityNodeInfo> =
        labels.flatMap { label ->
            root.findAccessibilityNodeInfosByText(label).filter { it.text?.toString()?.trim() == label }
        }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent
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

    /** Reads Settings' own labels in the current language, with fallbacks if it renamed them. */
    private fun resolveSettingsLabels() {
        val probe = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        settingsPackage = packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
            ?: DEFAULT_SETTINGS_PACKAGE
        val resources = try {
            packageManager.getResourcesForApplication(settingsPackage)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
        fun strings(vararg names: String): List<String> = names.mapNotNull { name ->
            val id = resources?.getIdentifier(name, "string", settingsPackage) ?: 0
            if (id == 0) null else resources?.getString(id)?.trim()
        }
        storageLabels = (strings("storage_label", "storage_settings", "storage_settings_for_app") +
            FALLBACK_STORAGE_LABELS).toSet()
        clearCacheLabels = (strings("clear_cache_btn_text") + FALLBACK_CLEAR_CACHE_LABELS).toSet()
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = LayoutInflater.from(Appearance.themedContext(this))
            .inflate(R.layout.overlay_cleaning, null)
        view.findViewById<Button>(R.id.stopButton).setOnClickListener {
            finish(stopped = true, reopenApp = true)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so Settings stays the active window the service works on.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun updateOverlay(target: CleanTarget) {
        val view = overlay ?: return
        val done = total - queue.size - 1
        view.findViewById<TextView>(R.id.overlayProgress).text =
            getString(R.string.overlay_progress, done + 1, total, target.label)
        view.findViewById<LinearProgressIndicator>(R.id.overlayBar).apply {
            max = total
            setProgressCompat(done, true)
        }
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    private fun finish(stopped: Boolean, reopenApp: Boolean) {
        if (!isRunning) return
        handler.removeCallbacksAndMessages(null)
        step = Step.IDLE
        current = null
        queue.clear()
        hideOverlay()
        if (!reopenApp) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_CLEANED, cleaned)
                .putExtra(EXTRA_FAILED, failed)
                .putExtra(EXTRA_STOPPED, stopped)
        )
    }

    companion object {
        const val EXTRA_CLEANED = "cleaned"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_STOPPED = "stopped"

        private const val DEFAULT_SETTINGS_PACKAGE = "com.android.settings"
        private val FALLBACK_STORAGE_LABELS = listOf("Storage", "Storage & cache", "فضای ذخیره‌سازی")
        private val FALLBACK_CLEAR_CACHE_LABELS = listOf("Clear cache", "پاک کردن حافظه پنهان")

        private const val FIRST_TICK_MS = 300L
        private const val POLL_MS = 250L
        private const val EVENT_DEBOUNCE_MS = 120L
        private const val AFTER_CLEAR_MS = 350L
        private const val STEP_TIMEOUT_MS = 5000L
        private const val MAX_SCROLLS = 6

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
