/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.recent

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.util.startActivitySafe
import java.text.DateFormat
import java.util.Date

/** Recently opened files and recently visited folders, newest first. */
class RecentActivity : AppActivity() {
    private class Row(val title: String?, val path: String?, val time: Long, val isFolder: Boolean)

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private val rows = mutableListOf<Row>()

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Row = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (rows[position].path == null) 0 else 1

        override fun isEnabled(position: Int): Boolean = rows[position].path != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val inflater = layoutInflater
            if (row.path == null) {
                val view = convertView
                    ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                (view as TextView).apply {
                    text = row.title
                    textSize = 14f
                    setPadding(paddingLeft, (16 * resources.displayMetrics.density).toInt(),
                        paddingRight, 0)
                    alpha = 0.7f
                    isEnabled = false
                }
                return view
            }
            val view = convertView
                ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val name = row.path.substringAfterLast('/').ifEmpty { row.path }
            view.findViewById<TextView>(android.R.id.text1).text = name
            view.findViewById<TextView>(android.R.id.text2).text =
                row.path + "\n" + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT
                ).format(Date(row.time))
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply { setTitle(R.string.recent_title) }
        listView = ListView(this).apply {
            adapter = this@RecentActivity.adapter
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> onRowClicked(rows[position]) }
        }
        emptyText = TextView(this).apply {
            setText(R.string.recent_empty)
            gravity = android.view.Gravity.CENTER
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        reload()
    }

    private fun reload() {
        rows.clear()
        val folders = RecentFiles.folders
        val files = RecentFiles.files
        if (files.isNotEmpty()) {
            rows += Row(getString(R.string.recent_files), null, 0, false)
            files.mapTo(rows) { Row(null, it.path, it.time, false) }
        }
        if (folders.isNotEmpty()) {
            rows += Row(getString(R.string.recent_folders), null, 0, true)
            folders.mapTo(rows) { Row(null, it.path, it.time, true) }
        }
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun onRowClicked(row: Row) {
        val pathString = row.path ?: return
        val path = try {
            Paths.get(pathString)
        } catch (e: Exception) {
            return
        }
        if (row.isFolder) {
            startActivity(FileListActivity.createViewIntent(path))
        } else {
            val mimeType = MimeType.guessFromPath(pathString)
            startActivitySafe(OpenFileActivity.createIntent(path, mimeType))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, R.string.recent_clear)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            .isEnabled = rows.isNotEmpty()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_CLEAR -> {
                RecentFiles.clear()
                reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    companion object {
        private const val MENU_CLEAR = 1
    }
}
