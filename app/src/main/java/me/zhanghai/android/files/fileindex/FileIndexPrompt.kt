/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.os.Environment

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/** Asks once whether the user wants a search index, and which top-level folders to include. */
object FileIndexPrompt {
    fun showIfNeeded(activity: AppCompatActivity) {
        if (Settings.FILE_INDEX_PROMPT_SHOWN.valueCompat) {
            return
        }
        Settings.FILE_INDEX_PROMPT_SHOWN.putValue(true)
        activity.window.decorView.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                showDialog(activity)
            }
        }
    }

    fun showDialog(activity: AppCompatActivity, onDone: (() -> Unit)? = null) {
        val storage = Environment.getExternalStorageDirectory()
        val folders = (storage.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            .sortedBy { it.name.lowercase() }
        if (folders.isEmpty()) {
            onDone?.invoke()
            return
        }
        val names = folders.map { it.name }.toTypedArray()
        val checked = BooleanArray(folders.size) { true }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_index_prompt_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.file_index_prompt_enable) { _, _ ->
                val selected = folders.filterIndexed { index, _ -> checked[index] }
                if (selected.isNotEmpty()) {
                    for (folder in selected) {
                        FileIndex.addRoot(folder.path)
                    }
                    Settings.FILE_INDEX_ENABLED.putValue(true)
                    FileIndex.startScan(force = true)
                }
                onDone?.invoke()
            }
            .setNegativeButton(R.string.file_index_prompt_not_now) { _, _ -> onDone?.invoke() }
            .show()
    }
}
