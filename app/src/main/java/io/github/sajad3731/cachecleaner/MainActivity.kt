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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ThemedActivity() {
    private lateinit var totalSize: TextView
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var listTitle: TextView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var engineChip: Chip
    private lateinit var primaryButton: MaterialButton

    private lateinit var scanner: CacheScanner
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppCacheAdapter

    // Scanning hundreds of packages takes long enough to freeze the UI, so it runs here.
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var scanning = false
    private var cleaningSilently = false
    private var lastResult = ScanResult(emptyList())

    // Cache total before a cleaning started, to report how much it freed after the rescan.
    private var bytesBeforeClean = -1L
    private var pendingReport: CleanReport? = null

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
        engineChip = header.findViewById(R.id.engineChip)
        primaryButton = header.findViewById(R.id.primaryButton)
        engineChip.setOnClickListener { openAdvanced() }

        adapter = AppCacheAdapter(header)
        list.adapter = adapter

        Shizuku.addBinderReceivedListener(binderReceived)
        if (savedInstanceState == null) takeReport(intent)
    }

    // Shizuku may call this off the main thread.
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread(::showEngine) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeReport(intent)
    }

    override fun onResume() {
        super.onResume()
        ShizukuEngine.requestBinderIfMissing(this)
        showEngine()
        // Permissions and cache sizes can both change while the user is in Settings.
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun openAdvanced() = startActivity(Intent(this, AdvancedActivity::class.java))

    private fun setStatus(text: CharSequence?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setStatus(resId: Int) = setStatus(getString(resId))

    /** Shows which engine Clean all will use, so the Shizuku state is visible at a glance. */
    private fun showEngine() {
        val text = when {
            !ShizukuEngine.isReady(this) -> R.string.engine_chip_accessibility
            ShizukuEngine.isRoot() -> R.string.engine_chip_shizuku_root
            // Trimming would clear a skipped app too, so Clean all falls back to Accessibility.
            CleanPlan.skippedAppsHoldCache(prefs, lastResult) -> R.string.engine_chip_accessibility_skipped
            else -> R.string.engine_chip_shizuku_adb
        }
        engineChip.setText(text)
    }

    private fun takeReport(intent: Intent?) {
        if (intent?.hasExtra(CacheCleanerService.EXTRA_CLEANED) != true) return
        pendingReport = CleanReport(
            cleaned = intent.getIntExtra(CacheCleanerService.EXTRA_CLEANED, 0),
            failed = intent.getIntExtra(CacheCleanerService.EXTRA_FAILED, 0),
            stopped = intent.getBooleanExtra(CacheCleanerService.EXTRA_STOPPED, false),
            engine = Engine.ACCESSIBILITY,
        )
        intent.removeExtra(CacheCleanerService.EXTRA_CLEANED)
    }

    private fun refresh() {
        if (!scanner.hasUsageAccess()) {
            showNeedsUsageAccess()
            return
        }
        if (scanning || cleaningSilently || CacheCleanerService.instance?.isRunning == true) return
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
        showEngine()
        scanProgress.visibility = View.GONE
        showReport(result)
        adapter.submit(result.apps, prefs.excluded)
        primaryButton.isEnabled = true
        listTitle.visibility = if (result.apps.isEmpty()) View.GONE else View.VISIBLE
        if (result.apps.isEmpty()) {
            totalSize.visibility = View.GONE
            summary.setText(R.string.nothing_found)
            primaryButton.setText(R.string.rescan)
            primaryButton.icon = null
            primaryButton.setOnClickListener { setStatus(null); refresh() }
            return
        }
        totalSize.visibility = View.VISIBLE
        totalSize.text = Formatter.formatFileSize(this, result.totalBytes)
        summary.text = resources.getQuantityString(R.plurals.scan_summary, result.apps.size, result.apps.size)
        primaryButton.setText(R.string.clean_all)
        primaryButton.setIconResource(R.drawable.ic_delete_sweep)
        primaryButton.setOnClickListener { cleanAll() }
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
                engine = report.engine,
                scheduled = false,
                outcome = when {
                    report.failedToRun -> Outcome.FAILED
                    report.stopped -> Outcome.STOPPED
                    else -> Outcome.DONE
                },
            )
        )
        if (report.failedToRun) {
            setStatus(R.string.shizuku_failed)
            return
        }
        val size = Formatter.formatFileSize(this, freed ?: 0L)
        val text = StringBuilder()
        text.append(
            when {
                report.cleaned == HistoryEntry.ALL_APPS -> getString(R.string.report_freed_all, size)
                freed != null -> resources.getQuantityString(R.plurals.report_freed, report.cleaned, size, report.cleaned)
                else -> resources.getQuantityString(R.plurals.report_cleaned, report.cleaned, report.cleaned)
            }
        )
        if (report.failed > 0) {
            text.append(' ').append(resources.getQuantityString(R.plurals.report_failed, report.failed, report.failed))
        }
        if (report.stopped) text.append(' ').append(getString(R.string.report_stopped))
        setStatus(text)
    }

    private fun cleanAll() {
        val scan = lastResult
        if (CleanPlan.canCleanSilently(this, prefs, scan)) {
            cleanSilently { CleanPlan.cleanSilently(this, prefs, scan, ::showSilentProgress) }
            return
        }
        val targets = CleanPlan.targets(this, prefs, scan).map { CleanTarget(it.packageName, it.label) }
        startCleaning(targets, includeSelf = true)
    }

    private fun cleanOne(app: AppCache) {
        if (app.packageName == packageName) {
            bytesBeforeClean = lastResult.totalBytes
            clearOwnCache()
            pendingReport = CleanReport(cleaned = 1, failed = 0, stopped = false, engine = Engine.ACCESSIBILITY)
            refresh()
            return
        }
        // As root, Shizuku clears one app without opening Settings; as shell, Android refuses.
        if (ShizukuEngine.isReady(this) && ShizukuEngine.isRoot()) {
            cleanSilently { ShizukuEngine.clearEach(this, listOf(app.packageName)) }
            return
        }
        startCleaning(listOf(CleanTarget(app.packageName, app.label)), includeSelf = false, fallback = app)
    }

    /** Runs a Shizuku clean on the worker thread; [work] returns apps cleaned, or null on failure. */
    private fun cleanSilently(work: () -> Int?) {
        cleaningSilently = true
        bytesBeforeClean = lastResult.totalBytes
        setStatus(null)
        showBusy(getString(R.string.cleaning))
        clearOwnCache()
        io.execute {
            val cleaned = work()
            runOnUiThread {
                cleaningSilently = false
                pendingReport = CleanReport(
                    cleaned = cleaned ?: 0,
                    failed = 0,
                    stopped = false,
                    engine = Engine.SHIZUKU,
                    failedToRun = cleaned == null,
                )
                if (!isFinishing && !isDestroyed) refresh()
            }
        }
    }

    private fun showSilentProgress(done: Int, total: Int) {
        runOnUiThread { summary.text = getString(R.string.cleaning_progress, done, total) }
    }

    private fun toggleSkipped(app: AppCache) {
        val skipped = prefs.toggleExcluded(app.packageName)
        setStatus(getString(if (skipped) R.string.skip_added else R.string.skip_removed, app.label))
        adapter.submit(lastResult.apps, prefs.excluded)
        showEngine()
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
        if (includeSelf) clearOwnCache()
        if (targets.isEmpty()) {
            pendingReport = CleanReport(cleaned = 0, failed = 0, stopped = false, engine = Engine.ACCESSIBILITY)
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
        val failed: Int,
        val stopped: Boolean,
        val engine: Engine,
        val failedToRun: Boolean = false,
    )

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1
        const val SKIPPED_ALPHA = 0.45f
    }
}
