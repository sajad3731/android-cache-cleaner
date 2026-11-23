package io.github.sajad3731.cachecleaner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Cleaning service state, appearance, what Clean all skips, the reminder and the history. */
class AdvancedActivity : ThemedActivity() {
    private lateinit var prefs: Prefs
    private lateinit var serviceStatus: TextView
    private lateinit var serviceButton: MaterialButton
    private lateinit var skipAppsButton: MaterialButton
    private lateinit var scheduleNote: TextView
    private lateinit var historyTotal: TextView
    private lateinit var historyList: TextView

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    // The reminder is a notification: Android 13+ asks for it when the reminder is turned on.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { showReminderNote() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)
        prefs = Prefs(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        serviceStatus = findViewById(R.id.serviceStatus)
        serviceButton = findViewById(R.id.serviceButton)
        skipAppsButton = findViewById(R.id.skipAppsButton)
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
        serviceButton.setOnClickListener {
            if (!tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                serviceStatus.setText(R.string.settings_unavailable)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The service or notifications may have been turned on or off in Settings.
        showService()
        showSkipped()
        showReminderNote()
        showHistory()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showService() {
        val on = CacheCleanerService.isEnabled(this)
        serviceStatus.setText(if (on) R.string.service_on else R.string.service_off)
        serviceButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun showSkipped() {
        val skipped = prefs.excluded.size
        skipAppsButton.text = resources.getQuantityString(R.plurals.skip_apps_button, skipped, skipped)
    }

    private fun showReminderNote() {
        val blocked = prefs.schedule != Schedule.OFF && !ReminderJobService.canNotify(this)
        scheduleNote.setText(if (blocked) R.string.schedule_note_blocked else R.string.schedule_note)
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
        group.check(options.firstOrNull { it.second == current }?.first ?: R.id.minSizeAny)
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
        group.check(options.first { it.second == prefs.schedule }.first)
        group.addOnButtonCheckedListener { _, id, checked ->
            val schedule = options.firstOrNull { it.first == id }?.second
            if (!checked || schedule == null || schedule == prefs.schedule) return@addOnButtonCheckedListener
            prefs.schedule = schedule
            ReminderJobService.apply(this)
            if (schedule == Schedule.OFF) ReminderJobService.cancel(this) else askForNotifications()
            showReminderNote()
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                showSkipped()
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
        if (entry.freedBytes >= 0) parts += Formatter.formatFileSize(this, entry.freedBytes)
        parts += resources.getQuantityString(R.plurals.history_apps, entry.apps, entry.apps)
        if (entry.outcome == Outcome.STOPPED) parts += getString(R.string.history_stopped)
        if (entry.fromReminder) parts += getString(R.string.history_from_reminder)
        return parts.joinToString(" · ")
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
