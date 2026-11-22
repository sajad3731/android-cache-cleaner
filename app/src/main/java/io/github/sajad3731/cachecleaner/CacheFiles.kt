package io.github.sajad3731.cachecleaner

import java.io.File

/**
 * Size and delete walks over cache directories. Symbolic links are never followed: their
 * targets live outside the cache, and a link cycle would recurse forever.
 *
 * [isSymlink] is injected because the app checks with `Os.lstat` (java.nio.file needs API 26),
 * which only exists on a device; JVM tests pass a java.nio.file check instead.
 */
class CacheFiles(private val isSymlink: (File) -> Boolean) {

    fun size(file: File): Long {
        if (isSymlink(file)) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::size) ?: 0L
    }

    /** Deletes the contents of [directory], keeping the directory. True if everything went. */
    fun clearContents(directory: File): Boolean {
        val entries = directory.listFiles() ?: return false
        var cleared = true
        entries.forEach { entry ->
            if (!delete(entry)) cleared = false
        }
        return cleared
    }

    fun delete(file: File): Boolean {
        // Remove a symbolic link itself, never what it points to, so deletion cannot reach
        // files outside the cache. This also removes dangling links, which exists() misses.
        if (isSymlink(file)) return file.delete()
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            var deleted = true
            children.forEach { child ->
                if (!delete(child)) deleted = false
            }
            if (!deleted) return false
        }
        return file.delete()
    }
}
