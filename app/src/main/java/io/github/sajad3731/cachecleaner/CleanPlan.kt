package io.github.sajad3731.cachecleaner

import android.content.Context

/** Decides how Clean all runs. Shared by the main screen and the scheduled job. */
object CleanPlan {
    /** Apps Clean all goes through one by one: not this app, not skipped, big enough. */
    fun targets(context: Context, prefs: Prefs, scan: ScanResult): List<AppCache> {
        val excluded = prefs.excluded
        val minBytes = prefs.minCleanBytes
        return scan.apps.filter {
            it.packageName != context.packageName && it.packageName !in excluded && it.cacheBytes >= minBytes
        }
    }

    /**
     * Whether Shizuku can clean without opening Settings. As root it clears app by app, so skipped
     * apps stay untouched. As shell it can only trim every cache at once, which is fine only while
     * no skipped app holds cache. Without a scan, any skipped app counts.
     */
    fun canCleanSilently(context: Context, prefs: Prefs, scan: ScanResult?): Boolean {
        if (!ShizukuEngine.isReady(context)) return false
        if (ShizukuEngine.isRoot() && scan != null) return true
        return !skippedAppsHoldCache(prefs, scan)
    }

    fun skippedAppsHoldCache(prefs: Prefs, scan: ScanResult?): Boolean {
        val excluded = prefs.excluded
        if (excluded.isEmpty()) return false
        return scan?.apps?.any { it.packageName in excluded } ?: true
    }

    /**
     * Cleans through Shizuku. Blocks; call it off the main thread, after [canCleanSilently].
     * Returns the number of apps cleaned, [HistoryEntry.ALL_APPS] after a trim, or null on failure.
     */
    fun cleanSilently(
        context: Context,
        prefs: Prefs,
        scan: ScanResult?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int? {
        if (ShizukuEngine.isRoot() && scan != null) {
            val packages = targets(context, prefs, scan).map { it.packageName }
            return ShizukuEngine.clearEach(context, packages) { onProgress(it, packages.size) }
        }
        return if (ShizukuEngine.trimAll(context)) HistoryEntry.ALL_APPS else null
    }
}
