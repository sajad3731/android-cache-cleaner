package io.github.sajad3731.cachecleaner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Junk files on shared storage, by group, with sizes; the user picks what to delete. */
class JunkActivity : ThemedActivity() {
    private lateinit var size: TextView
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var button: MaterialButton
    private lateinit var scanner: JunkScanner
    private lateinit var adapter: JunkAdapter

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var busy = false

    // Kept across rotation and theme changes, so the user's choices are not reset to defaults.
    private lateinit var state: JunkState
    private var result: JunkResult?
        get() = state.result
        set(value) {
            state.result = value
        }
    private val selected: MutableSet<String> get() = state.selected
    private val expanded: MutableSet<JunkGroup> get() = state.expanded

    // Android 8 to 10 have no All files access; there the storage permission does the same.
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_junk)
        scanner = JunkScanner(this)
        state = ViewModelProvider(this)[JunkState::class.java]
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.junkList)
        list.layoutManager = LinearLayoutManager(this)
        val header = LayoutInflater.from(this).inflate(R.layout.header_junk, list, false)
        size = header.findViewById(R.id.junkSize)
        summary = header.findViewById(R.id.junkSummary)
        status = header.findViewById(R.id.junkStatus)
        progress = header.findViewById(R.id.junkProgress)
        button = header.findViewById(R.id.junkButton)
        adapter = JunkAdapter(header)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Access may have been granted in Settings meanwhile; files may have changed too.
        if (result == null || !scanner.hasAccess()) refresh()
    }

    override fun onDestroy() {
        // A scan or delete still running reports to this screen only; the next one scans again.
        if (busy) state.result = null
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        if (busy) return
        if (!scanner.hasAccess()) {
            showNeedsAccess()
            return
        }
        busy = true
        showBusy(getString(R.string.junk_scanning))
        io.execute {
            val scanned = scanner.scan()
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result = scanned
                selected.clear()
                scanned.items.filter { it.preselected }.mapTo(selected) { it.key }
                showResult()
            }
        }
    }

    private fun showBusy(text: String) {
        size.visibility = View.GONE
        summary.text = text
        progress.visibility = View.VISIBLE
        button.isEnabled = false
    }

    private fun showNeedsAccess() {
        result = null
        adapter.rebuild()
        size.visibility = View.GONE
        progress.visibility = View.GONE
        summary.setText(R.string.junk_need_access)
        button.isEnabled = true
        button.icon = null
        button.setText(R.string.junk_allow_access)
        button.setOnClickListener { requestAccess() }
    }

    private fun showResult() {
        val scanned = result ?: return
        progress.visibility = View.GONE
        adapter.rebuild()
        if (scanned.items.isEmpty()) {
            size.visibility = View.GONE
            summary.setText(R.string.junk_nothing_found)
            button.isEnabled = true
            button.icon = null
            button.setText(R.string.rescan)
            button.setOnClickListener { setStatus(null); refresh() }
            return
        }
        showSelection()
        button.setIconResource(R.drawable.ic_delete_sweep)
        button.setText(R.string.junk_delete)
        button.setOnClickListener { confirmDelete() }
    }

    /** Header numbers follow the checkboxes. */
    private fun showSelection() {
        val scanned = result ?: return
        val chosen = scanned.items.filter { it.key in selected }
        size.visibility = View.VISIBLE
        size.text = Formatter.formatFileSize(this, chosen.sumOf { it.bytes })
        summary.text = getString(
            R.string.junk_summary,
            resources.getQuantityString(R.plurals.junk_items, chosen.size, chosen.size),
            Formatter.formatFileSize(this, scanned.totalBytes),
        )
        button.isEnabled = chosen.isNotEmpty()
    }

    private fun confirmDelete() {
        val chosen = result?.items?.filter { it.key in selected }.orEmpty()
        if (chosen.isEmpty()) return
        val sizeText = Formatter.formatFileSize(this, chosen.sumOf { it.bytes })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.junk_confirm_title)
            .setMessage(resources.getQuantityString(R.plurals.junk_confirm_message, chosen.size, chosen.size, sizeText))
            .setPositiveButton(R.string.junk_delete) { _, _ -> delete(chosen) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(items: List<JunkItem>) {
        if (busy) return
        busy = true
        setStatus(null)
        showBusy(getString(R.string.junk_deleting))
        io.execute {
            val outcome = scanner.delete(items)
            val rescanned = scanner.scan()
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result = rescanned
                selected.clear()
                rescanned.items.filter { it.preselected }.mapTo(selected) { it.key }
                showResult()
                val text = StringBuilder(getString(R.string.junk_freed, Formatter.formatFileSize(this, outcome.freedBytes)))
                if (outcome.failed > 0) {
                    text.append(' ').append(resources.getQuantityString(R.plurals.junk_failed, outcome.failed, outcome.failed))
                }
                setStatus(text)
            }
        }
    }

    private fun setStatus(text: CharSequence?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val opened = tryStart(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null))
            ) || tryStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            if (!opened) setStatus(getString(R.string.settings_unavailable))
        } else {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }

    private fun groupTitle(group: JunkGroup): Int = when (group) {
        JunkGroup.LEFTOVERS -> R.string.junk_group_leftovers
        JunkGroup.APKS -> R.string.junk_group_apks
        JunkGroup.THUMBNAILS -> R.string.junk_group_thumbnails
        JunkGroup.EMPTY_FOLDERS -> R.string.junk_group_empty
        JunkGroup.OLD_DOWNLOADS -> R.string.junk_group_downloads
    }

    private fun groupDescription(group: JunkGroup): String = when (group) {
        JunkGroup.LEFTOVERS -> getString(R.string.junk_group_leftovers_summary)
        JunkGroup.APKS -> getString(R.string.junk_group_apks_summary)
        JunkGroup.THUMBNAILS -> getString(R.string.junk_group_thumbnails_summary)
        JunkGroup.EMPTY_FOLDERS -> getString(R.string.junk_group_empty_summary)
        JunkGroup.OLD_DOWNLOADS -> getString(
            R.string.junk_group_downloads_summary,
            Formatter.formatShortFileSize(this, JunkScanner.OLD_DOWNLOAD_MIN_BYTES),
            OLD_DOWNLOAD_DAYS,
        )
    }

    private sealed class Row {
        object Header : Row()
        data class Group(val group: JunkGroup, val items: List<JunkItem>) : Row()
        data class Item(val item: JunkItem, val last: Boolean) : Row()
    }

    /** Header card, then one segmented block per group: its row, and its items while expanded. */
    private inner class JunkAdapter(private val header: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = listOf(Row.Header)
        private val outer = resources.getDimension(R.dimen.list_corner_outer)
        private val inner = resources.getDimension(R.dimen.list_corner_inner)
        private val gap = resources.getDimensionPixelSize(R.dimen.group_gap)

        // Groups open and close, and selection changes counts across a block.
        @Suppress("NotifyDataSetChanged")
        fun rebuild() {
            val scanned = result
            val newRows = mutableListOf<Row>(Row.Header)
            if (scanned != null) {
                for (group in JunkGroup.entries) {
                    val items = scanned.group(group)
                    if (items.isEmpty()) continue
                    newRows += Row.Group(group, items)
                    if (group in expanded) items.forEachIndexed { i, item -> newRows += Row.Item(item, i == items.lastIndex) }
                }
            }
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            Row.Header -> TYPE_HEADER
            is Row.Group -> TYPE_GROUP
            is Row.Item -> TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_HEADER -> object : RecyclerView.ViewHolder(header) {}
            TYPE_GROUP -> GroupHolder(layoutInflater.inflate(R.layout.item_junk_group, parent, false))
            else -> ItemHolder(layoutInflater.inflate(R.layout.item_junk_file, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Group -> (holder as GroupHolder).bind(row, position)
                is Row.Item -> (holder as ItemHolder).bind(row)
                Row.Header -> Unit
            }
        }

        private fun shape(card: MaterialCardView, top: Float, bottom: Float) {
            card.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(top).setTopRightCornerSize(top)
                .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
                .build()
        }

        inner class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view as MaterialCardView
            private val check: MaterialCheckBox = view.findViewById(R.id.groupCheck)
            private val title: TextView = view.findViewById(R.id.groupTitle)
            private val description: TextView = view.findViewById(R.id.groupDescription)
            private val count: TextView = view.findViewById(R.id.groupCount)
            private val expand: ImageView = view.findViewById(R.id.groupExpand)

            fun bind(row: Row.Group, position: Int) {
                val open = row.group in expanded
                shape(card, outer, if (open) inner else outer)
                (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (position == 1) 0 else gap
                title.setText(groupTitle(row.group))
                description.text = groupDescription(row.group)
                val bytes = row.items.sumOf { it.bytes }
                val items = resources.getQuantityString(R.plurals.junk_items, row.items.size, row.items.size)
                count.text = if (bytes > 0) "$items · ${Formatter.formatFileSize(this@JunkActivity, bytes)}" else items
                expand.rotation = if (open) 180f else 0f

                val chosen = row.items.count { it.key in selected }
                check.setOnCheckedChangeListener(null)
                check.checkedState = when (chosen) {
                    0 -> MaterialCheckBox.STATE_UNCHECKED
                    row.items.size -> MaterialCheckBox.STATE_CHECKED
                    else -> MaterialCheckBox.STATE_INDETERMINATE
                }
                check.contentDescription = getString(R.string.junk_select_group, title.text)
                check.setOnClickListener {
                    // Partly or fully chosen: clear the group; otherwise choose all of it.
                    if (chosen == 0) row.items.mapTo(selected) { it.key } else row.items.forEach { selected.remove(it.key) }
                    showSelection()
                    rebuild()
                }
                card.setOnClickListener {
                    if (!expanded.add(row.group)) expanded.remove(row.group)
                    rebuild()
                }
            }
        }

        inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view as MaterialCardView
            private val check: MaterialCheckBox = view.findViewById(R.id.fileCheck)
            private val name: TextView = view.findViewById(R.id.fileName)
            private val path: TextView = view.findViewById(R.id.filePath)
            private val size: TextView = view.findViewById(R.id.fileSize)

            fun bind(row: Row.Item) {
                val item = row.item
                shape(card, inner, if (row.last) outer else inner)
                name.text = item.file.name
                path.text = scanner.relativePath(item.file.parentFile ?: item.file).ifEmpty { "/" }
                size.text = if (item.bytes > 0) Formatter.formatShortFileSize(this@JunkActivity, item.bytes) else ""
                check.setOnCheckedChangeListener(null)
                check.isChecked = item.key in selected
                check.contentDescription = getString(R.string.junk_select_item, item.file.name)
                val toggle = {
                    if (!selected.add(item.key)) selected.remove(item.key)
                    showSelection()
                    // The group row's checkbox may change from all to some.
                    rebuild()
                }
                check.setOnClickListener { toggle() }
                card.setOnClickListener { toggle() }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GROUP = 1
        const val TYPE_ITEM = 2

        val OLD_DOWNLOAD_DAYS = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(JunkScanner.OLD_DOWNLOAD_MIN_AGE_MS).toInt()
    }
}

/**
 * The scan and the user's choices, kept while the screen is recreated. Only items marked
 * preselected start ticked: leftovers, bundles and old downloads may still be wanted.
 */
class JunkState : ViewModel() {
    var result: JunkResult? = null
    val selected = HashSet<String>()
    val expanded = HashSet<JunkGroup>()
}
