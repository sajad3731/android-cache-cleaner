package io.github.sajad3731.cachecleaner

import android.content.Context

/** Which apps Clean all goes through. Shared by the main screen and the reminder. */
object CleanPlan {
    /** Not this app, not skipped, at least the size limit (1 MB by default; 0 means every app with cache). */
    fun targets(context: Context, prefs: Prefs, scan: ScanResult): List<AppCache> {
        val excluded = prefs.excluded
        val minBytes = prefs.minCleanBytes
        return scan.apps.filter {
            it.packageName != context.packageName && it.packageName !in excluded &&
                it.cacheBytes > 0 && it.cacheBytes >= minBytes
        }
    }
}
