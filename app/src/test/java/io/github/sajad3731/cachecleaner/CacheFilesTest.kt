package io.github.sajad3731.cachecleaner

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cacheFiles = CacheFiles { Files.isSymbolicLink(it.toPath()) }

    private lateinit var cache: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        cache = temp.newFolder("cache")
        outside = temp.newFolder("outside")
    }

    private fun write(file: File, bytes: Int): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    private fun link(link: File, target: File): File =
        Files.createSymbolicLink(link.toPath(), target.toPath()).toFile()

    @Test
    fun sizeSumsNestedFiles() {
        write(File(cache, "a"), 10)
        write(File(cache, "dir/b"), 20)
        write(File(cache, "dir/deeper/c"), 30)

        assertEquals(60L, cacheFiles.size(cache))
    }

    @Test
    fun sizeIgnoresSymlinkTargets() {
        write(File(cache, "own"), 5)
        write(File(outside, "big"), 1000)
        link(File(cache, "fileLink"), File(outside, "big"))
        link(File(cache, "dirLink"), outside)

        assertEquals(5L, cacheFiles.size(cache))
    }

    @Test
    fun sizeTerminatesOnLinkCycle() {
        val dir = File(cache, "dir").apply { mkdirs() }
        link(File(dir, "loop"), cache)

        assertEquals(0L, cacheFiles.size(cache))
    }

    @Test
    fun clearContentsEmptiesButKeepsDirectory() {
        write(File(cache, "a"), 1)
        write(File(cache, "dir/b"), 1)

        assertTrue(cacheFiles.clearContents(cache))
        assertTrue(cache.isDirectory)
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test
    fun clearContentsRemovesLinksButNotTargets() {
        val target = write(File(outside, "keep"), 7)
        val nested = write(File(outside, "sub/keep2"), 7)
        link(File(cache, "fileLink"), target)
        link(File(cache, "dirLink"), outside)

        assertTrue(cacheFiles.clearContents(cache))
        assertEquals(0, cache.listFiles()!!.size)
        assertTrue(target.isFile)
        assertTrue(nested.isFile)
    }

    @Test
    fun clearContentsRemovesDanglingLink() {
        link(File(cache, "dangling"), File(outside, "missing"))

        assertTrue(cacheFiles.clearContents(cache))
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test
    fun clearContentsReportsMissingDirectory() {
        assertFalse(cacheFiles.clearContents(File(temp.root, "nope")))
    }

    @Test
    fun deleteMissingFileCountsAsDeleted() {
        assertTrue(cacheFiles.delete(File(cache, "nope")))
    }
}
