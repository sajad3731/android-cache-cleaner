package io.github.sajad3731.cachecleaner

import android.content.Context

enum class Engine { ACCESSIBILITY, SHIZUKU }

enum class Schedule { OFF, DAILY, WEEKLY }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Why a run ended. SKIPPED_* runs freed nothing. */
enum class Outcome { DONE, STOPPED, SKIPPED_NO_SHIZUKU, SKIPPED_EXCLUSIONS, FAILED }

data class HistoryEntry(
    val time: Long,
    /** Bytes freed, or -1 when Usage access was off and nothing could be measured. */
    val freedBytes: Long,
    /** Apps cleaned, or [ALL_APPS] when Android trimmed every app at once. */
    val apps: Int,
    val engine: Engine,
    val scheduled: Boolean,
    val outcome: Outcome,
) {
    companion object {
        const val ALL_APPS = -1
    }
}

/** Advanced settings and the freed-space history, kept on the device only. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Apps Clean all and scheduled cleans leave alone. */
    var excluded: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED, value).apply()

    /** Clean all skips smaller caches: each app costs about a second in Settings. */
    var minCleanBytes: Long
        get() = prefs.getLong(KEY_MIN_BYTES, DEFAULT_MIN_BYTES)
        set(value) = prefs.edit().putLong(KEY_MIN_BYTES, value).apply()

    var schedule: Schedule
        get() = enumOr(prefs.getString(KEY_SCHEDULE, null), Schedule.OFF)
        set(value) = prefs.edit().putString(KEY_SCHEDULE, value.name).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING, true)
        set(value) = prefs.edit().putBoolean(KEY_CHARGING, value).apply()

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
        listOf(e.time, e.freedBytes, e.apps, e.engine.name, e.scheduled, e.outcome.name).joinToString(",")

    private fun parse(line: String): HistoryEntry? {
        val f = line.split(',')
        if (f.size != 6) return null
        return HistoryEntry(
            time = f[0].toLongOrNull() ?: return null,
            freedBytes = f[1].toLongOrNull() ?: return null,
            apps = f[2].toIntOrNull() ?: return null,
            engine = enumOr(f[3], Engine.ACCESSIBILITY),
            scheduled = f[4].toBoolean(),
            outcome = enumOr(f[5], Outcome.DONE),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val DEFAULT_MIN_BYTES = 1L shl 20
        private const val MAX_HISTORY = 30
        private const val KEY_EXCLUDED = "excluded"
        private const val KEY_MIN_BYTES = "min_clean_bytes"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_HISTORY = "history"
        private const val KEY_THEME = "theme"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
