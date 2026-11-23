package io.github.sajad3731.cachecleaner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ThemedActivity() {
    private lateinit var totalSize: TextView
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var listTitle: TextView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var primaryButton: MaterialButton

    private lateinit var scanner: CacheScanner
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppCacheAdapter

    // Scanning hundreds of packages takes long enough to freeze the UI, so it runs here.
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var scanning = false
    // Set when the reminder notification opened the app: Clean all starts after the scan.
    private var autoCleanAfterScan = false
    private var cleanFromReminder = false
    private var lastResult = ScanResult(emptyList())

    // Cache total before a cleaning started, to report how much it freed after the rescan.
    private var bytesBeforeClean = -1L
    private var pendingReport: CleanReport? = null
    // 1 when Clean all also emptied this app's own cache, which the service does not count.
    private var selfCleaned = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanner = CacheScanner(this)
        prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.action_advanced) return@setOnMenuItemClickListener false
            openAdvanced()
            true
        }

        val list = findViewById<RecyclerView>(R.id.appList)
        // Before inflating the header: RecyclerView needs it to make the header's layout params.
        list.layoutManager = LinearLayoutManager(this)
        val header = LayoutInflater.from(this).inflate(R.layout.header_summary, list, false)
        totalSize = header.findViewById(R.id.totalSize)
        summary = header.findViewById(R.id.summary)
        status = header.findViewById(R.id.status)
        listTitle = header.findViewById(R.id.listTitle)
        scanProgress = header.findViewById(R.id.scanProgress)
        primaryButton = header.findViewById(R.id.primaryButton)
        header.findViewById<View>(R.id.junkCard).setOnClickListener {
            startActivity(Intent(this, JunkActivity::class.java))
        }

        adapter = AppCacheAdapter(header)
        list.adapter = adapter

        if (savedInstanceState == null) takeIntent(intent) else restoreState(savedInstanceState)
    }

    // A rotation or theme change while the rescan runs must not lose the run's report or the
    // reminder's request to clean.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AUTO_CLEAN, autoCleanAfterScan)
        outState.putBoolean(STATE_FROM_REMINDER, cleanFromReminder)
        outState.putLong(STATE_BYTES_BEFORE, bytesBeforeClean)
        outState.putInt(STATE_SELF_CLEANED, selfCleaned)
        pendingReport?.let { report ->
            outState.putInt(CacheCleanerService.EXTRA_CLEANED, report.cleaned)
            outState.putStringArrayList(CacheCleanerService.EXTRA_FAILED_NAMES, ArrayList(report.failedNames))
            outState.putBoolean(CacheCleanerService.EXTRA_STOPPED, report.stopped)
            outState.putInt(CacheCleanerService.EXTRA_NOT_REACHED, report.notReached)
        }
    }

    private fun restoreState(state: Bundle) {
        autoCleanAfterScan = state.getBoolean(STATE_AUTO_CLEAN)
        cleanFromReminder = state.getBoolean(STATE_FROM_REMINDER)
        bytesBeforeClean = state.getLong(STATE_BYTES_BEFORE, -1L)
        selfCleaned = state.getInt(STATE_SELF_CLEANED)
        pendingReport = readReport(state)
    }

    private fun readReport(extras: Bundle): CleanReport? {
        if (!extras.containsKey(CacheCleanerService.EXTRA_CLEANED)) return null
        return CleanReport(
            cleaned = extras.getInt(CacheCleanerService.EXTRA_CLEANED, 0),
            failedNames = extras.getStringArrayList(CacheCleanerService.EXTRA_FAILED_NAMES).orEmpty(),
            stopped = extras.getBoolean(CacheCleanerService.EXTRA_STOPPED, false),
            notReached = extras.getInt(CacheCleanerService.EXTRA_NOT_REACHED, 0),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions and cache sizes can both change while the user is in Settings.
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun openAdvanced() = startActivity(Intent(this, AdvancedActivity::class.java))

    private fun setStatus(text: CharSequence?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setStatus(resId: Int) = setStatus(getString(resId))

    private fun takeIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_START_CLEAN, false)) {
            // The user tapped the reminder: clean once the fresh scan is in.
            intent.removeExtra(EXTRA_START_CLEAN)
            ReminderJobService.cancel(this)
            autoCleanAfterScan = true
            cleanFromReminder = true
        }
        val report = intent.extras?.let(::readReport) ?: return
        pendingReport = report.copy(cleaned = report.cleaned + selfCleaned)
        selfCleaned = 0
        intent.removeExtra(CacheCleanerService.EXTRA_CLEANED)
    }

    private fun refresh() {
        if (!scanner.hasUsageAccess()) {
            showNeedsUsageAccess()
            return
        }
        if (scanning || CacheCleanerService.instance?.isRunning == true) return
        scanning = true
        showBusy(getString(R.string.scanning))
        io.execute {
            val result = scanner.scan()
            runOnUiThread {
                scanning = false
                if (!isFinishing && !isDestroyed) showResult(result)
            }
        }
    }

    private fun showBusy(text: String) {
        totalSize.text = ""
        totalSize.visibility = View.GONE
        summary.text = text
        scanProgress.visibility = View.VISIBLE
        primaryButton.isEnabled = false
    }

    private fun showNeedsUsageAccess() {
        adapter.submit(emptyList(), emptySet())
        scanProgress.visibility = View.GONE
        totalSize.visibility = View.GONE
        summary.setText(R.string.need_usage_access)
        listTitle.visibility = View.GONE
        primaryButton.isEnabled = true
        primaryButton.setText(R.string.grant_usage_access)
        primaryButton.icon = null
        primaryButton.setOnClickListener { openUsageAccessSettings() }
    }

    private fun showResult(result: ScanResult) {
        lastResult = result
        scanProgress.visibility = View.GONE
        showReport(result)
        // Caches under the size limit are left out: Clean all skips them, so the list does too.
        val shown = shownApps()
        val hidden = result.apps.size - shown.size
        val limit = getString(R.string.min_size_mb, (prefs.minCleanBytes / Prefs.MB).toInt())
        adapter.submit(shown, prefs.excluded)
        primaryButton.isEnabled = true
        listTitle.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        if (shown.isEmpty()) {
            totalSize.visibility = View.GONE
            if (hidden > 0) summary.text = getString(R.string.nothing_over_limit, limit) else summary.setText(R.string.nothing_found)
            primaryButton.setText(R.string.rescan)
            primaryButton.icon = null
            primaryButton.setOnClickListener { setStatus(null); refresh() }
            return
        }
        totalSize.visibility = View.VISIBLE
        totalSize.text = Formatter.formatFileSize(this, shown.sumOf { it.cacheBytes })
        val found = resources.getQuantityString(R.plurals.scan_summary, shown.size, shown.size)
        summary.text = if (hidden > 0) {
            found + " " + resources.getQuantityString(R.plurals.scan_hidden, hidden, hidden, limit)
        } else {
            found
        }
        primaryButton.setText(R.string.clean_all)
        primaryButton.setIconResource(R.drawable.ic_delete_sweep)
        primaryButton.setOnClickListener { cleanFromReminder = false; cleanAll() }
        if (autoCleanAfterScan) {
            autoCleanAfterScan = false
            cleanAll()
        }
    }

    private fun showReport(result: ScanResult) {
        val report = pendingReport ?: return
        pendingReport = null
        val freed = if (bytesBeforeClean >= 0) {
            (bytesBeforeClean - result.totalBytes).coerceAtLeast(0L)
        } else {
            null
        }
        bytesBeforeClean = -1L
        prefs.addHistory(
            HistoryEntry(
                time = System.currentTimeMillis(),
                freedBytes = freed ?: -1L,
                apps = report.cleaned,
                fromReminder = cleanFromReminder,
                outcome = if (report.stopped) Outcome.STOPPED else Outcome.DONE,
            )
        )
        cleanFromReminder = false
        val size = Formatter.formatFileSize(this, freed ?: 0L)
        val text = StringBuilder()
        text.append(
            when {
                freed != null -> resources.getQuantityString(R.plurals.report_freed, report.cleaned, size, report.cleaned)
                else -> resources.getQuantityString(R.plurals.report_cleaned, report.cleaned, report.cleaned)
            }
        )
        val failed = report.failedNames
        if (failed.isNotEmpty()) {
            text.append(' ').append(
                resources.getQuantityString(R.plurals.report_failed, failed.size, failed.size, failed.joinToString(getString(R.string.list_separator)))
            )
        }
        if (report.notReached > 0) {
            text.append(' ').append(resources.getQuantityString(R.plurals.report_not_reached, report.notReached, report.notReached))
        }
        if (report.stopped) text.append(' ').append(getString(R.string.report_stopped))
        setStatus(text)
    }

    /** Apps at or above the size limit; this app's own row too, when it holds that much. */
    private fun shownApps(): List<AppCache> {
        val minBytes = prefs.minCleanBytes
        return lastResult.apps.filter { it.cacheBytes >= minBytes }
    }

    private fun cleanAll() {
        val scan = lastResult
        val targets = CleanPlan.targets(this, prefs, scan).map { CleanTarget(it.packageName, it.label) }
        startCleaning(targets, includeSelf = true)
    }

    private fun cleanOne(app: AppCache) {
        if (app.packageName == packageName) {
            bytesBeforeClean = lastResult.totalBytes
            clearOwnCache()
            pendingReport = CleanReport(cleaned = 1)
            refresh()
            return
        }
        cleanFromReminder = false
        startCleaning(listOf(CleanTarget(app.packageName, app.label)), includeSelf = false, fallback = app)
    }

    private fun toggleSkipped(app: AppCache) {
        val skipped = prefs.toggleExcluded(app.packageName)
        setStatus(getString(if (skipped) R.string.skip_added else R.string.skip_removed, app.label))
        adapter.submit(shownApps(), prefs.excluded)
    }

    private fun startCleaning(targets: List<CleanTarget>, includeSelf: Boolean, fallback: AppCache? = null) {
        val service = CacheCleanerService.instance
        if (service == null) {
            // Turned on but not bound yet, as right after the app was updated or restarted.
            if (CacheCleanerService.isEnabled(this)) setStatus(R.string.service_starting) else showEnableDialog(fallback)
            return
        }
        bytesBeforeClean = lastResult.totalBytes
        setStatus(null)
        selfCleaned = 0
        if (includeSelf) {
            if (lastResult.apps.any { it.packageName == packageName }) selfCleaned = 1
            clearOwnCache()
        }
        if (targets.isEmpty()) {
            pendingReport = CleanReport(cleaned = selfCleaned)
            selfCleaned = 0
            refresh()
            return
        }
        service.clean(targets)
    }

    /** This app may delete its own cache directly; no Settings round trip needed. */
    private fun clearOwnCache() {
        (listOf(cacheDir) + externalCacheDirs.filterNotNull()).distinct().forEach { dir ->
            dir.listFiles()?.forEach(File::deleteRecursively)
        }
    }

    private fun showEnableDialog(fallback: AppCache?) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enable_title)
            .setMessage(R.string.enable_message)
            .setPositiveButton(R.string.enable_turn_on) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.not_now, null)
        if (fallback != null) {
            dialog.setNeutralButton(R.string.open_app_settings) { _, _ ->
                openAppDetails(fallback.packageName)
            }
        }
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        // The per-service details page is reserved for system apps, so open the list.
        if (!tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) setStatus(R.string.settings_unavailable)
    }

    private fun openUsageAccessSettings() {
        // Some devices accept the package to jump straight to this app; all accept the list.
        val opened = tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri())) ||
            tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        if (!opened) setStatus(R.string.settings_unavailable)
    }

    private fun openAppDetails(packageName: String) {
        val opened = tryStart(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
        if (!opened) setStatus(R.string.settings_unavailable)
    }

    private fun packageUri(): Uri = Uri.fromParts("package", packageName, null)

    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }

    /** The summary card, then one row per app, drawn as one segmented list. */
    private inner class AppCacheAdapter(private val header: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var apps: List<AppCache> = emptyList()
        private var skipped: Set<String> = emptySet()
        private val outer = resources.getDimension(R.dimen.list_corner_outer)
        private val inner = resources.getDimension(R.dimen.list_corner_inner)

        // The whole list changes after every scan.
        @Suppress("NotifyDataSetChanged")
        fun submit(newApps: List<AppCache>, skippedApps: Set<String>) {
            apps = newApps
            skipped = skippedApps
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = apps.size + 1
        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(header) {}
            } else {
                AppHolder(layoutInflater.inflate(R.layout.item_app_cache, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AppHolder) holder.bind(apps[position - 1], position - 1)
        }

        inner class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view as MaterialCardView
            private val icon: ImageView = view.findViewById(R.id.appIcon)
            private val name: TextView = view.findViewById(R.id.appName)
            private val cache: TextView = view.findViewById(R.id.appCache)
            private val clear: MaterialButton = view.findViewById(R.id.clearButton)

            fun bind(app: AppCache, index: Int) {
                val top = if (index == 0) outer else inner
                val bottom = if (index == apps.lastIndex) outer else inner
                card.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(top).setTopRightCornerSize(top)
                    .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
                    .build()
                val isSkipped = app.packageName in skipped
                icon.setImageDrawable(app.icon)
                icon.alpha = if (isSkipped) SKIPPED_ALPHA else 1f
                name.text = app.label
                val size = Formatter.formatFileSize(this@MainActivity, app.cacheBytes)
                cache.text = if (isSkipped) getString(R.string.size_skipped, size) else size
                clear.contentDescription = getString(R.string.clear_app_description, app.label)
                clear.setOnClickListener { cleanOne(app) }
                card.setOnClickListener { openAppDetails(app.packageName) }
                card.setOnLongClickListener {
                    toggleSkipped(app)
                    true
                }
            }
        }
    }

    private data class CleanReport(
        val cleaned: Int,
        val failedNames: List<String> = emptyList(),
        val stopped: Boolean = false,
        val notReached: Int = 0,
    )

    companion object {
        /** Set by the reminder notification: start Clean all after the scan. */
        const val EXTRA_START_CLEAN = "start_clean"

        private const val STATE_AUTO_CLEAN = "auto_clean"
        private const val STATE_FROM_REMINDER = "from_reminder"
        private const val STATE_BYTES_BEFORE = "bytes_before"
        private const val STATE_SELF_CLEANED = "self_cleaned"

        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
        private const val SKIPPED_ALPHA = 0.45f
    }
}
