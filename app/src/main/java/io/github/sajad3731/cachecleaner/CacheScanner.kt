package io.github.sajad3731.cachecleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import java.io.IOException

data class AppCache(
    val packageName: String,
    val label: String,
    /** Null when the scan skipped icons (the reminder only sums sizes). */
    val icon: Drawable?,
    val cacheBytes: Long,
)

data class ScanResult(val apps: List<AppCache>) {
    val totalBytes: Long = apps.sumOf { it.cacheBytes }
}

/**
 * Reads every installed app's cache size from Android's storage stats. Sizes come from the
 * system, not from walking other apps' folders, which Android does not allow.
 */
class CacheScanner(private val context: Context) {
    private val packageManager = context.packageManager
    private val storageStats = context.getSystemService(StorageStatsManager::class.java)

    /** Usage access is what StorageStatsManager needs to report other apps' sizes. */
    // Both AppOps checks are deprecated on API 36 with no replacement below it.
    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun scan(withIcons: Boolean = true): ScanResult {
        val user = Process.myUserHandle()
        val apps = installedApps().mapNotNull { info ->
            val cacheBytes = try {
                // cacheBytes covers both internal and external cache of the package.
                storageStats.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, info.packageName, user
                ).cacheBytes
            } catch (ignored: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            } catch (ignored: IOException) {
                return@mapNotNull null
            } catch (ignored: SecurityException) {
                // Usage access revoked mid-scan, or a package the system will not report on.
                return@mapNotNull null
            }
            if (cacheBytes <= 0L) return@mapNotNull null
            AppCache(
                packageName = info.packageName,
                label = info.loadLabel(packageManager).toString(),
                icon = if (withIcons) info.loadIcon(packageManager) else null,
                cacheBytes = cacheBytes,
            )
        }
        return ScanResult(apps.sortedByDescending { it.cacheBytes })
    }

    /** Every installed app except this one, by label, for choosing which apps Clean all skips. */
    fun appLabels(): List<Pair<String, String>> =
        installedApps()
            .filter { it.packageName != context.packageName }
            .map { it.packageName to it.loadLabel(packageManager).toString() }
            .sortedBy { it.second.lowercase() }

    private fun installedApps(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
}
