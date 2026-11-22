package io.github.sajad3731.cachecleaner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Engine status, what Clean all skips, the schedule and the freed-space history. */
class AdvancedActivity : ThemedActivity() {
    private lateinit var prefs: Prefs
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: MaterialButton
    private lateinit var skipAppsButton: MaterialButton
    private lateinit var skipNote: TextView
    private lateinit var scheduleNote: TextView
    private lateinit var historyTotal: TextView
    private lateinit var historyList: TextView

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    // Shizuku may call these off the main thread.
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread(::showEngine) }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread(::showEngine) }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread(::showEngine) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)
        prefs = Prefs(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        shizukuStatus = findViewById(R.id.shizukuStatus)
        shizukuButton = findViewById(R.id.shizukuButton)
        skipAppsButton = findViewById(R.id.skipAppsButton)
        skipNote = findViewById(R.id.skipNote)
        scheduleNote = findViewById(R.id.scheduleNote)
        historyTotal = findViewById(R.id.historyTotal)
        historyList = findViewById(R.id.historyList)

        setUpAppearance()
        setUpMinSize()
        setUpSchedule()
        skipAppsButton.setOnClickListener { chooseSkippedApps() }
        findViewById<MaterialButton>(R.id.privacyPolicyButton).setOnClickListener { showPrivacyPolicy() }
        findViewById<MaterialButton>(R.id.clearHistoryButton).setOnClickListener {
            prefs.clearHistory()
            showHistory()
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onResume() {
        super.onResume()
        // Shizuku may have been started or stopped, and a scheduled clean may have run.
        ShizukuEngine.requestBinderIfMissing(this)
        showEngine()
        showHistory()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showEngine() {
        if (isFinishing || isDestroyed) return
        val state = ShizukuEngine.state(this)
        val ready = state == ShizukuEngine.State.READY
        val root = ready && ShizukuEngine.isRoot()
        shizukuStatus.setText(
            when (state) {
                ShizukuEngine.State.NOT_INSTALLED -> R.string.shizuku_not_installed
                ShizukuEngine.State.NOT_RUNNING -> R.string.shizuku_not_running
                ShizukuEngine.State.NO_PERMISSION -> R.string.shizuku_no_permission
                ShizukuEngine.State.READY -> if (root) R.string.shizuku_ready_root else R.string.shizuku_ready_adb
            }
        )
        shizukuButton.visibility = if (ready) View.GONE else View.VISIBLE
        when (state) {
            ShizukuEngine.State.NOT_INSTALLED -> {
                shizukuButton.setText(R.string.shizuku_get)
                shizukuButton.setOnClickListener { getShizuku() }
            }
            ShizukuEngine.State.NOT_RUNNING -> {
                shizukuButton.setText(R.string.shizuku_open)
                shizukuButton.setOnClickListener { openShizuku() }
            }
            ShizukuEngine.State.NO_PERMISSION -> {
                shizukuButton.setText(R.string.shizuku_allow)
                shizukuButton.setOnClickListener { requestShizukuPermission() }
            }
            ShizukuEngine.State.READY -> shizukuButton.setOnClickListener(null)
        }

        val skipped = prefs.excluded.size
        skipAppsButton.text = resources.getQuantityString(R.plurals.skip_apps_button, skipped, skipped)
        skipNote.text = if (ready && !root && skipped > 0) {
            getString(R.string.skip_note) + "\n\n" + getString(R.string.skip_note_adb)
        } else {
            getString(R.string.skip_note)
        }
        scheduleNote.setText(
            when {
                !ready -> R.string.schedule_note_needs_shizuku
                root -> R.string.schedule_note_root
                else -> R.string.schedule_note_adb
            }
        )
    }

    private fun setUpAppearance() {
        val themes = listOf(
            R.id.themeSystem to ThemeMode.SYSTEM,
            R.id.themeLight to ThemeMode.LIGHT,
            R.id.themeDark to ThemeMode.DARK,
        )
        val group = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        group.check(themes.first { it.second == prefs.themeMode }.first)
        group.addOnButtonCheckedListener { _, id, checked ->
            val mode = themes.firstOrNull { it.first == id }?.second
            if (!checked || mode == null || mode == prefs.themeMode) return@addOnButtonCheckedListener
            prefs.themeMode = mode
            // AppCompat recreates open activities with the new night mode.
            Appearance.applyNightMode(prefs)
        }

        findViewById<MaterialSwitch>(R.id.pureBlackSwitch).apply {
            isChecked = prefs.pureBlack
            setOnCheckedChangeListener { _, checked ->
                prefs.pureBlack = checked
                recreate()
            }
        }
        findViewById<MaterialSwitch>(R.id.dynamicColorSwitch).apply {
            // Wallpaper colours exist from Android 12.
            val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColors.isDynamicColorAvailable()
            visibility = if (available) View.VISIBLE else View.GONE
            this@AdvancedActivity.findViewById<View>(R.id.dynamicColorNote).visibility = visibility
            isChecked = prefs.dynamicColor
            setOnCheckedChangeListener { _, checked ->
                prefs.dynamicColor = checked
                recreate()
            }
        }
    }

    private fun setUpMinSize() {
        val options = listOf(
            R.id.minSizeAny to 0L,
            R.id.minSize1 to (1L shl 20),
            R.id.minSize10 to (10L shl 20),
            R.id.minSize50 to (50L shl 20),
        )
        findViewById<Chip>(R.id.minSizeAny).setText(R.string.min_size_any)
        findViewById<Chip>(R.id.minSize1).text = getString(R.string.min_size_mb, 1)
        findViewById<Chip>(R.id.minSize10).text = getString(R.string.min_size_mb, 10)
        findViewById<Chip>(R.id.minSize50).text = getString(R.string.min_size_mb, 50)
        val group = findViewById<ChipGroup>(R.id.minSizeGroup)
        val current = prefs.minCleanBytes
        group.check(options.firstOrNull { it.second == current }?.first ?: R.id.minSize1)
        group.setOnCheckedStateChangeListener { _, ids ->
            options.firstOrNull { it.first == ids.firstOrNull() }?.let { prefs.minCleanBytes = it.second }
        }
    }

    private fun setUpSchedule() {
        val options = listOf(
            R.id.scheduleOff to Schedule.OFF,
            R.id.scheduleDaily to Schedule.DAILY,
            R.id.scheduleWeekly to Schedule.WEEKLY,
        )
        val group = findViewById<MaterialButtonToggleGroup>(R.id.scheduleGroup)
        val charging = findViewById<MaterialSwitch>(R.id.chargingSwitch)
        group.check(options.first { it.second == prefs.schedule }.first)
        charging.isChecked = prefs.chargingOnly
        charging.isEnabled = prefs.schedule != Schedule.OFF
        group.addOnButtonCheckedListener { _, id, checked ->
            val schedule = options.firstOrNull { it.first == id }?.second
            if (!checked || schedule == null) return@addOnButtonCheckedListener
            prefs.schedule = schedule
            charging.isEnabled = schedule != Schedule.OFF
            CleanJobService.apply(this)
        }
        charging.setOnCheckedChangeListener { _, checked ->
            prefs.chargingOnly = checked
            CleanJobService.apply(this)
        }
    }

    private fun chooseSkippedApps() {
        skipAppsButton.isEnabled = false
        io.execute {
            val apps = CacheScanner(this).appLabels()
            runOnUiThread {
                skipAppsButton.isEnabled = true
                if (!isFinishing && !isDestroyed) showSkipDialog(apps)
            }
        }
    }

    private fun showSkipDialog(apps: List<Pair<String, String>>) {
        val chosen = prefs.excluded.toMutableSet()
        val labels = apps.map { it.second }.toTypedArray()
        val checked = apps.map { it.first in chosen }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skip_dialog_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen.add(apps[which].first) else chosen.remove(apps[which].first)
            }
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.excluded = chosen
                showEngine()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHistory() {
        val history = prefs.history()
        if (history.isEmpty()) {
            historyTotal.visibility = View.GONE
            historyList.setText(R.string.history_empty)
            return
        }
        val total = history.filter { it.freedBytes > 0 }.sumOf { it.freedBytes }
        historyTotal.visibility = View.VISIBLE
        historyTotal.text = getString(R.string.history_total, Formatter.formatFileSize(this, total))
        historyList.text = history.joinToString("\n") { describe(it) }
    }

    private fun describe(entry: HistoryEntry): String {
        val parts = mutableListOf(
            DateUtils.formatDateTime(
                this,
                entry.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
        )
        if (entry.scheduled) parts += getString(R.string.history_scheduled)
        when (entry.outcome) {
            Outcome.SKIPPED_NO_SHIZUKU -> parts += getString(R.string.history_skipped_no_shizuku)
            Outcome.SKIPPED_EXCLUSIONS -> parts += getString(R.string.history_skipped_exclusions)
            Outcome.FAILED -> parts += getString(R.string.history_failed)
            Outcome.DONE, Outcome.STOPPED -> {
                if (entry.freedBytes >= 0) parts += Formatter.formatFileSize(this, entry.freedBytes)
                parts += if (entry.apps == HistoryEntry.ALL_APPS) {
                    getString(R.string.history_all_apps)
                } else {
                    resources.getQuantityString(R.plurals.history_apps, entry.apps, entry.apps)
                }
                if (entry.outcome == Outcome.STOPPED) parts += getString(R.string.history_stopped)
            }
        }
        parts += getString(
            if (entry.engine == Engine.SHIZUKU) R.string.engine_shizuku else R.string.engine_accessibility
        )
        return parts.joinToString(" · ")
    }

    private fun getShizuku() {
        // Store first (Bazaar and Myket handle market links), then the project's download page.
        val opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuEngine.PACKAGE}"))) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.shizuku_download_url))))
        if (!opened) shizukuStatus.setText(R.string.settings_unavailable)
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(ShizukuEngine.PACKAGE)
        if (launch == null || !tryStart(launch)) shizukuStatus.setText(R.string.settings_unavailable)
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            showEngine()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            showEngine()
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Denied with "don't ask again": only the Shizuku app can grant it now.
            shizukuStatus.setText(R.string.shizuku_denied)
        } else {
            Shizuku.requestPermission(ShizukuEngine.PERMISSION_REQUEST)
        }
    }

    private fun showPrivacyPolicy() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_policy_title)
            .setMessage(R.string.privacy_policy_content)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.view_online_policy) { _, _ ->
                tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            }
            .show()
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
}
