/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.util.copyText

/** Shows what this device supports and lets the user run the index benchmark. */
object IndexDiagnosticsDialog {
    private const val BENCHMARK_ENTRIES = 200_000

    fun show(context: Context) {
        val text = TextView(context).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val handler = Handler(Looper.getMainLooper())
        val lines = mutableListOf<String>()
        fun append(line: String) {
            handler.post {
                lines += line
                text.text = lines.joinToString("\n")
            }
        }
        append("Checking device...")
        Thread {
            lines.clear()
            IndexDiagnostics.capabilities().forEach { append(it) }
        }.start()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_file_index_diagnostics_title)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(R.string.settings_file_index_diagnostics_run, null)
            .setNeutralButton(R.string.settings_file_index_diagnostics_synthetic, null)
            .setNegativeButton(R.string.settings_file_index_diagnostics_copy, null)
            .setOnDismissListener { IndexDiagnostics.cancelRequested = true }
            .create()
        dialog.show()
        var running = false
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (running) {
                return@setOnClickListener
            }
            running = true
            Thread {
                try {
                    IndexDiagnostics.runBenchmark(
                        Environment.getExternalStorageDirectory(), BENCHMARK_ENTRIES
                    ) { append(it) }
                } catch (e: Throwable) {
                    append("Failed: $e")
                } finally {
                    running = false
                }
            }.start()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (running) {
                return@setOnClickListener
            }
            val sizes = intArrayOf(200_000, 1_000_000, 2_000_000)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_file_index_diagnostics_synthetic)
                .setItems(arrayOf("200,000", "1,000,000", "2,000,000")) { _, which ->
                    running = true
                    Thread {
                        try {
                            IndexDiagnostics.runSyntheticBenchmark(sizes[which]) { append(it) }
                        } catch (e: Throwable) {
                            append("Failed: $e")
                        } finally {
                            running = false
                        }
                    }.start()
                }
                .show()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            clipboardManager.copyText(text.text, context)
        }
    }
}
