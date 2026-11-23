package io.github.sajad3731.cachecleaner

import android.content.Context

enum class Schedule { OFF, DAILY, WEEKLY }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Why a run ended. */
enum class Outcome { DONE, STOPPED }

data class HistoryEntry(
    val time: Long,
    /** Bytes freed, or -1 when Usage access was off and nothing could be measured. */
    val freedBytes: Long,
    val apps: Int,
    /** Started from the cleaning reminder notification. */
    val fromReminder: Boolean,
    val outcome: Outcome,
)

/** Advanced settings and the freed-space history, kept on the device only. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Apps Clean all and scheduled cleans leave alone. */
    var excluded: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED, value).apply()

    /**
     * Clean all skips smaller caches, and the main list hides them. 0 cleans every app that
     * holds any cache. Each app costs the same time however small its cache, so 1 MB by default.
     */
    var minCleanBytes: Long
        // Whole MB; older versions stored 1024-based sizes (10 MiB), read as the same MB.
        get() = prefs.getLong(KEY_MIN_BYTES, DEFAULT_MIN_BYTES) / MB * MB
        set(value) = prefs.edit().putLong(KEY_MIN_BYTES, value).apply()

    var schedule: Schedule
        get() = enumOr(prefs.getString(KEY_SCHEDULE, null), Schedule.OFF)
        set(value) = prefs.edit().putString(KEY_SCHEDULE, value.name).apply()

    var themeMode: ThemeMode
        get() = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Black surfaces in the dark theme, for OLED screens. */
    var pureBlack: Boolean
        get() = prefs.getBoolean(KEY_PURE_BLACK, false)
        set(value) = prefs.edit().putBoolean(KEY_PURE_BLACK, value).apply()

    /** Colours from the wallpaper (Android 12+) instead of the app's teal. */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    fun toggleExcluded(packageName: String): Boolean {
        val now = excluded.toMutableSet()
        val added = now.add(packageName)
        if (!added) now.remove(packageName)
        excluded = now
        return added
    }

    /** Newest first. */
    fun history(): List<HistoryEntry> =
        prefs.getString(KEY_HISTORY, "")!!.lineSequence().mapNotNull(::parse).toList()

    @Synchronized
    fun addHistory(entry: HistoryEntry) {
        val lines = listOf(format(entry)) + history().take(MAX_HISTORY - 1).map(::format)
        prefs.edit().putString(KEY_HISTORY, lines.joinToString("\n")).apply()
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    private fun format(e: HistoryEntry) =
        listOf(e.time, e.freedBytes, e.apps, e.fromReminder, e.outcome.name).joinToString(",")

    private fun parse(line: String): HistoryEntry? {
        // Older versions also stored the engine as the fourth field.
        val f = line.split(',').let { if (it.size == 6) it.filterIndexed { i, _ -> i != 3 } else it }
        if (f.size != 5) return null
        val apps = f[2].toIntOrNull() ?: return null
        // Runs that freed nothing (skipped, failed) or trimmed all apps at once are gone.
        val outcome = enumValues<Outcome>().firstOrNull { it.name == f[4] } ?: return null
        if (apps < 0) return null
        return HistoryEntry(
            time = f[0].toLongOrNull() ?: return null,
            freedBytes = f[1].toLongOrNull() ?: return null,
            apps = apps,
            fromReminder = f[3].toBoolean(),
            outcome = outcome,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        /** Android's Formatter counts in 1000s since 8.0; the size limit matches the sizes shown. */
        const val MB = 1_000_000L
        const val DEFAULT_MIN_BYTES = MB

        /** The size limit slider goes up to this many MB, in 1 MB steps. */
        const val MAX_MIN_MB = 50
        private const val MAX_HISTORY = 30
        private const val KEY_EXCLUDED = "excluded"
        private const val KEY_MIN_BYTES = "min_clean_bytes"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_HISTORY = "history"
        private const val KEY_THEME = "theme"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
