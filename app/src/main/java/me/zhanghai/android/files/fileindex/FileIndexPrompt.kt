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

/**
 * Asks once whether the user wants a search index: either everything on the internal storage, or
 * a hand-picked set of its top-level folders.
 */
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
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_index_prompt_title)
            .setMessage(R.string.file_index_prompt_message)
            .setPositiveButton(R.string.file_index_prompt_all) { _, _ ->
                // Index everything under the storage root, including every top-level folder.
                FileIndex.addRoot(Environment.getExternalStorageDirectory().path)
                Settings.FILE_INDEX_ENABLED.putValue(true)
                FileIndex.startScan(force = true)
                onDone?.invoke()
            }
            .setNegativeButton(R.string.file_index_prompt_choose) { _, _ ->
                showFolderChooser(activity, onDone)
            }
            .setNeutralButton(R.string.file_index_prompt_not_now) { _, _ -> onDone?.invoke() }
            .setOnCancelListener { onDone?.invoke() }
            .show()
    }

    private fun showFolderChooser(activity: AppCompatActivity, onDone: (() -> Unit)?) {
        val storage = Environment.getExternalStorageDirectory()
        val folders = (storage.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        if (folders.isEmpty()) {
            onDone?.invoke()
            return
        }
        val names = folders.map { it.name }.toTypedArray()
        val checked = BooleanArray(folders.size) { true }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_index_prompt_choose_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.file_index_prompt_enable) { _, _ ->
                val selected = folders.filterIndexed { index, _ -> checked[index] }
                if (selected.isNotEmpty()) {
                    // Each folder is its own root: a search is answered from the index only
                    // inside these folders (see the partial-search setting for parent folders).
                    for (folder in selected) {
                        FileIndex.addRoot(folder.path)
                    }
                    Settings.FILE_INDEX_ENABLED.putValue(true)
                    FileIndex.startScan(force = true)
                }
                onDone?.invoke()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone?.invoke() }
            .setOnCancelListener { onDone?.invoke() }
            .show()
    }
}
