package com.example.cachecleaner

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var cacheSize: TextView
    private lateinit var status: TextView
    private lateinit var clearCacheButton: Button

    // File I/O stays off the main thread: a large cache can take long enough to walk or delete
    // to freeze the UI. A single thread also keeps size checks and deletion from overlapping.
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cacheSize = findViewById(R.id.cacheSize)
        status = findViewById(R.id.status)
        clearCacheButton = findViewById(R.id.clearCacheButton)
        clearCacheButton.setOnClickListener {
            clearOwnCache()
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openStorageSettings()
        }
        findViewById<Button>(R.id.privacyPolicyButton).setOnClickListener {
            showPrivacyPolicy()
        }
    }

    override fun onResume() {
        super.onResume()
        // The settings page this app opens has its own Clear cache control, so refresh on return.
        updateCacheSize()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun clearOwnCache() {
        clearCacheButton.isEnabled = false
        runInBackground(
            work = {
                var cleared = true
                cacheDirectories().forEach { directory ->
                    val entries = directory.listFiles()
                    if (entries == null) {
                        cleared = false
                    } else {
                        entries.forEach { entry ->
                            if (!deleteRecursively(entry)) cleared = false
                        }
                    }
                }
                cleared to cacheDirectories().sumOf(::directorySize)
            },
            onResult = { (cleared, size) ->
                status.setText(if (cleared) R.string.cache_cleared else R.string.cache_clear_incomplete)
                showCacheSize(size)
                clearCacheButton.isEnabled = true
            }
        )
    }

    private fun openStorageSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            status.setText(R.string.settings_unavailable)
        }
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_policy_title)
            .setMessage(R.string.privacy_policy_content)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.view_online_policy) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.privacy_policy_url))
                        )
                    )
                } catch (ignored: ActivityNotFoundException) {
                    status.setText(R.string.privacy_link_unavailable)
                }
            }
            .show()
    }

    private fun updateCacheSize() {
        runInBackground(
            work = { cacheDirectories().sumOf(::directorySize) },
            onResult = ::showCacheSize
        )
    }

    private fun showCacheSize(bytes: Long) {
        cacheSize.text = getString(R.string.cache_size, Formatter.formatFileSize(this, bytes))
    }

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val result = work()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) onResult(result)
            }
        }
    }

    private fun cacheDirectories(): List<File> =
        (listOf(cacheDir) + externalCacheDirs.filterNotNull()).distinct()

    private fun directorySize(file: File): Long {
        // Symbolic links are not followed: their targets live outside the cache, and a link cycle
        // would recurse forever.
        if (isSymlink(file)) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun deleteRecursively(file: File): Boolean {
        // Remove a symbolic link itself, never what it points to, so deletion cannot reach
        // files outside this app's cache. This also removes dangling links, which exists() misses.
        if (isSymlink(file)) return file.delete()
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            var deleted = true
            children.forEach { child ->
                if (!deleteRecursively(child)) deleted = false
            }
            if (!deleted) return false
        }
        return file.delete()
    }

    private fun isSymlink(file: File): Boolean =
        try {
            OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
        } catch (ignored: ErrnoException) {
            false
        }
}
