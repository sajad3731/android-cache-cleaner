package com.example.cachecleaner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private lateinit var cacheSize: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cacheSize = findViewById(R.id.cacheSize)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.clearCacheButton).setOnClickListener {
            clearOwnCache()
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openStorageSettings()
        }
        updateCacheSize()
    }

    private fun clearOwnCache() {
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
        status.setText(if (cleared) R.string.cache_cleared else R.string.cache_clear_incomplete)
        updateCacheSize()
    }

    private fun openStorageSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            status.setText(R.string.settings_unavailable)
        }
    }

    private fun updateCacheSize() {
        cacheSize.text = getString(
            R.string.cache_size,
            Formatter.formatFileSize(this, cacheDirectories().sumOf(::directorySize))
        )
    }

    private fun cacheDirectories(): List<File> =
        (listOf(cacheDir) + externalCacheDirs.filterNotNull()).distinct()

    private fun directorySize(file: File): Long {
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun deleteRecursively(file: File): Boolean {
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
}
