package io.github.sajad3731.cachecleaner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

enum class JunkGroup {
    /** Folders on shared storage named after apps that are no longer installed. */
    LEFTOVERS,
    /** App installers (.apk and bundles) anywhere on shared storage. */
    APKS,
    /** Thumbnail caches of the gallery; Android rebuilds them when needed. */
    THUMBNAILS,
    /** Folders with no files in them, at any depth. */
    EMPTY_FOLDERS,
    /** Big files in Download that have not changed for months. */
    OLD_DOWNLOADS,
}

data class JunkItem(
    val file: File,
    val group: JunkGroup,
    val bytes: Long,
    /** Delete what is inside and keep the folder itself (thumbnail caches). */
    val contentsOnly: Boolean = false,
    /** Safe to tick before the user looks: it holds nothing they could still want. */
    val preselected: Boolean = false,
) {
    val key: String get() = file.absolutePath
}

data class JunkResult(val items: List<JunkItem>) {
    val totalBytes: Long = items.sumOf { it.bytes }
    fun group(group: JunkGroup): List<JunkItem> = items.filter { it.group == group }
}

data class DeleteResult(val freedBytes: Long, val failed: Int)

/**
 * Finds junk on shared storage (the phone's internal "storage" folder). Needs All files access
 * on Android 11+, or the storage permission before. Android/data and Android/obb are never
 * read or touched: Android keeps them private to each app.
 */
class JunkScanner(private val context: Context) {
    // The shared storage root; the replacement APIs only cover single collections.
    @Suppress("DEPRECATION")
    private val root: File = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun scan(): JunkResult {
        val installed = installedPackages()
        val items = ArrayList<JunkItem>()
        val now = System.currentTimeMillis()

        // Android/media/<package>: shared media an app kept; useless once the app is gone.
        File(root, "Android/media").listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.lowercase() !in installed && looksLikePackage(dir.name, anyPrefix = true)) {
                items += JunkItem(dir, JunkGroup.LEFTOVERS, sizeOf(dir))
            }
        }

        val downloads = File(root, Environment.DIRECTORY_DOWNLOADS).absolutePath
        val protected = PROTECTED_FOLDERS.map { File(root, it).absolutePath }.toSet()

        /** Walks a folder; returns its file count and its topmost empty folders. */
        fun walk(dir: File, depth: Int): Pair<Int, List<File>> {
            val children = dir.listFiles() ?: return 1 to emptyList() // Unreadable: treat as not empty.
            var files = 0
            val empties = ArrayList<File>()
            for (child in children) {
                if (isLink(child)) {
                    files++
                    continue
                }
                if (child.isDirectory) {
                    when {
                        depth == 0 && child.name == "Android" -> files++ // Handled above, or private.
                        depth == 0 && looksLikePackage(child.name, anyPrefix = false) &&
                            !belongsToInstalledApp(child.name, installed) -> {
                            items += JunkItem(child, JunkGroup.LEFTOVERS, sizeOf(child))
                            files++
                        }
                        child.name.equals(".thumbnails", ignoreCase = true) -> {
                            val size = sizeOf(child)
                            if (size > 0) {
                                items += JunkItem(child, JunkGroup.THUMBNAILS, size, contentsOnly = true, preselected = true)
                            }
                            files++
                        }
                        depth >= MAX_DEPTH -> files++
                        else -> {
                            val (count, childEmpties) = walk(child, depth + 1)
                            files += count
                            empties += childEmpties
                            if (count == 0 && child.absolutePath !in protected) {
                                // Report the topmost empty folder only; deleting it removes the rest.
                                empties.removeAll { it.absolutePath.startsWith(child.absolutePath + "/") }
                                empties += child
                            }
                        }
                    }
                    continue
                }
                files++
                val name = child.name.lowercase()
                val size = child.length()
                when {
                    APK_EXTENSIONS.any { name.endsWith(it) } ->
                        items += JunkItem(child, JunkGroup.APKS, size, preselected = isInstalledCopy(child))
                    child.absolutePath.startsWith("$downloads/") && size >= OLD_DOWNLOAD_MIN_BYTES &&
                        now - child.lastModified() >= OLD_DOWNLOAD_MIN_AGE_MS ->
                        items += JunkItem(child, JunkGroup.OLD_DOWNLOADS, size)
                }
            }
            return files to empties
        }

        val (_, empties) = walk(root, 0)
        empties.forEach { items += JunkItem(it, JunkGroup.EMPTY_FOLDERS, 0, preselected = true) }
        return JunkResult(items.sortedWith(compareBy<JunkItem> { it.group }.thenByDescending { it.bytes }.thenBy { it.key }))
    }

    fun delete(items: Collection<JunkItem>): DeleteResult {
        var freed = 0L
        var failed = 0
        for (item in items) {
            if (!isDeletable(item.file)) {
                failed++
                continue
            }
            val ok = when {
                item.contentsOnly -> item.file.listFiles()?.map { it.deleteRecursively() }?.all { it } ?: false
                // Found empty, but a file may have arrived since: remove folders only, never files.
                item.group == JunkGroup.EMPTY_FOLDERS -> deleteEmptyFolders(item.file)
                else -> item.file.deleteRecursively()
            }
            if (ok) {
                freed += item.bytes
            } else {
                failed++
                freed += (item.bytes - sizeOf(item.file)).coerceAtLeast(0L)
            }
        }
        return DeleteResult(freed, failed)
    }

    /** Deletes a folder tree bottom-up; a folder that is no longer empty stays, with what is in it. */
    private fun deleteEmptyFolders(dir: File): Boolean {
        if (isLink(dir) || !dir.isDirectory) return false
        dir.listFiles()?.forEach { if (it.isDirectory && !isLink(it)) deleteEmptyFolders(it) }
        // File.delete() removes only an empty folder.
        return dir.delete()
    }

    /**
     * Whether a top-level folder may belong to an app that is installed. Apps often name their
     * folder after part of their package (ir.example for ir.example.app) or add to it.
     */
    private fun belongsToInstalledApp(name: String, installed: Set<String>): Boolean {
        val folder = name.lowercase()
        return installed.any { it == folder || it.startsWith("$folder.") || folder.startsWith("$it.") }
    }

    /**
     * An .apk of an app installed at the same or a newer version: the installed app does not
     * need it. Bundles (.apks, .xapk, .apkm) cannot be read this way and are never preselected:
     * people keep installers of apps their store does not offer.
     */
    private fun isInstalledCopy(file: File): Boolean {
        if (!file.name.endsWith(".apk", ignoreCase = true)) return false
        val pm = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
            @Suppress("DEPRECATION")
            val installed = pm.getPackageInfo(archive.packageName, 0)
            PackageInfoCompat.getLongVersionCode(installed) >= PackageInfoCompat.getLongVersionCode(archive)
        }.getOrDefault(false)
    }

    /** Path relative to the storage root, for display. */
    fun relativePath(file: File): String = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    /** Only below the storage root, never the root itself, never Android/data or Android/obb. */
    private fun isDeletable(file: File): Boolean {
        val path = file.absolutePath
        val base = root.absolutePath
        if (!path.startsWith("$base/")) return false
        return PRIVATE_FOLDERS.none { path == "$base/$it" || path.startsWith("$base/$it/") }
    }

    private fun sizeOf(file: File, depth: Int = 0): Long {
        if (isLink(file)) return 0
        if (!file.isDirectory) return file.length()
        if (depth >= MAX_DEPTH) return 0
        return file.listFiles()?.sumOf { sizeOf(it, depth + 1) } ?: 0
    }

    private fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

    private fun installedPackages(): Set<String> {
        // Apps uninstalled with "keep data" still count as installed: their folders stay.
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            context.packageManager.getInstalledApplications(flags)
        }
        return apps.map { it.packageName.lowercase() }.toSet()
    }

    companion object {
        /** Download files at least this big ... */
        const val OLD_DOWNLOAD_MIN_BYTES = 50L shl 20

        /** ... and unchanged for this long count as old downloads. */
        val OLD_DOWNLOAD_MIN_AGE_MS = TimeUnit.DAYS.toMillis(90)

        private const val MAX_DEPTH = 24
        private val APK_EXTENSIONS = listOf(".apk", ".apks", ".xapk", ".apkm")
        private val PRIVATE_FOLDERS = listOf("Android/data", "Android/obb")

        /** Standard folders stay even when empty: apps and the system expect them. */
        private val PROTECTED_FOLDERS = listOf(
            Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_NOTIFICATIONS, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_RINGTONES, "Audiobooks", "Recordings", "DCIM/Camera", "DCIM/Screenshots",
            "Pictures/Screenshots",
        )

        /** First parts of package names; a root folder must start with one to count as an app's. */
        private val PACKAGE_PREFIXES = setOf(
            "com", "org", "net", "io", "ir", "me", "de", "app", "dev", "co", "cn", "tv", "info", "android",
            "jp", "kr", "ru", "in", "us", "eu", "uk", "xyz", "air",
        )
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun looksLikePackage(name: String, anyPrefix: Boolean): Boolean =
            PACKAGE_NAME.matches(name) && (anyPrefix || name.substringBefore('.').lowercase() in PACKAGE_PREFIXES)
    }
}
