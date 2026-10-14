/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.util.copyText

/** Shows the index log with copy, share and clear buttons. */
object IndexLogDialog {
    fun show(context: Context) {
        val text = TextView(context).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(context).apply { addView(text) }
        fun load() {
            val log = IndexLog.read()
            text.text = log.ifEmpty { context.getString(R.string.settings_file_index_log_empty) }
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        load()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_file_index_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.settings_file_index_log_share, null)
            .setNeutralButton(R.string.settings_file_index_log_clear, null)
            .setNegativeButton(R.string.settings_file_index_diagnostics_copy, null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, IndexLog.read())
            context.startActivity(Intent.createChooser(intent, null))
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            IndexLog.clear()
            load()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            clipboardManager.copyText(IndexLog.read(), context)
        }
    }
}
