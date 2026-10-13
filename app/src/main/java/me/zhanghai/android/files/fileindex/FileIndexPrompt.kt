/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.ShizukuFileServiceLauncher
import me.zhanghai.android.files.util.AppExecutors
import java.io.File
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
                // Everything under the storage root, with the Shizuku/root/engine options.
                showOptions(activity, addStorageRoot = true, onDone = onDone)
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

    private class Access(val shizuku: Boolean, val root: Boolean)

    private fun detectAccess(): Access {
        val shizuku = try {
            ShizukuFileServiceLauncher.isAvailable()
        } catch (e: Throwable) {
            false
        }
        // Some root managers keep "su" out of the app's PATH, so also look at the usual places.
        val root = try {
            LibSuFileServiceLauncher.isSuAvailable()
        } catch (e: Throwable) {
            false
        } || listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/su/bin/su"
        ).any { File(it).exists() }
        return Access(shizuku, root)
    }

    private fun describe(activity: AppCompatActivity, title: Int, description: Int): CharSequence {
        val titleText = activity.getString(title)
        val text = SpannableString(titleText + "\n" + activity.getString(description))
        text.setSpan(
            RelativeSizeSpan(0.82f), titleText.length + 1, text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    /**
     * The full-scan options: app data through Shizuku/root, how much of the root file system to
     * add, and the search engine. Used by the first-run flow and from the settings.
     *
     * @param addStorageRoot also index everything on the internal storage (first-run flow)
     */
    fun showOptions(
        activity: AppCompatActivity,
        addStorageRoot: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        AppExecutors.io.execute {
            val access = detectAccess()
            val ftsSupported = FileIndexFts.isSupported
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showOptionsDialog(activity, addStorageRoot, access, ftsSupported, onDone)
                }
            }
        }
    }

    private fun showOptionsDialog(
        activity: AppCompatActivity,
        addStorageRoot: Boolean,
        access: Access,
        ftsSupported: Boolean,
        onDone: (() -> Unit)?
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.file_index_options_dialog, null)
        val appDataCheck = view.findViewById<CheckBox>(R.id.appDataCheck)
        val appDataNote = view.findViewById<TextView>(R.id.appDataNote)
        val rootGroup = view.findViewById<RadioGroup>(R.id.rootGroup)
        val rootNote = view.findViewById<TextView>(R.id.rootNote)
        val engineGroup = view.findViewById<RadioGroup>(R.id.engineGroup)
        val engineNote = view.findViewById<TextView>(R.id.engineNote)

        // Shizuku / app data.
        val appDataPossible = access.shizuku || access.root
        appDataCheck.isEnabled = appDataPossible
        appDataCheck.isChecked = appDataPossible && Settings.FILE_INDEX_APP_DATA.valueCompat
        appDataNote.setText(
            when {
                access.shizuku -> R.string.file_index_options_app_data_shizuku_ready
                access.root -> R.string.file_index_options_app_data_root_only
                else -> R.string.file_index_options_app_data_unavailable
            }
        )

        // Root levels.
        val levels = listOf(
            Triple(R.id.rootOff, R.string.file_index_options_root_off,
                R.string.file_index_options_root_off_description),
            Triple(R.id.rootDirs, R.string.file_index_options_root_dirs,
                R.string.file_index_options_root_dirs_description),
            Triple(R.id.rootUser, R.string.file_index_options_root_user,
                R.string.file_index_options_root_user_description),
            Triple(R.id.rootAll, R.string.file_index_options_root_all,
                R.string.file_index_options_root_all_description)
        )
        for ((index, level) in levels.withIndex()) {
            val button = view.findViewById<RadioButton>(level.first)
            button.text = describe(activity, level.second, level.third)
            button.isEnabled = index == 0 || access.root
        }
        val currentLevel = if (access.root) {
            Settings.FILE_INDEX_ROOT_LEVEL.valueCompat.coerceIn(0, levels.size - 1)
        } else {
            0
        }
        rootGroup.check(levels[currentLevel].first)
        rootNote.setText(
            if (access.root) {
                R.string.file_index_options_root_available
            } else {
                R.string.file_index_options_root_unavailable
            }
        )

        // Engine.
        val likeButton = view.findViewById<RadioButton>(R.id.engineLike)
        val ftsButton = view.findViewById<RadioButton>(R.id.engineFts)
        likeButton.text = describe(
            activity, R.string.file_index_options_engine_like,
            R.string.file_index_options_engine_like_description
        )
        ftsButton.text = describe(
            activity, R.string.file_index_options_engine_fts,
            R.string.file_index_options_engine_fts_description
        )
        ftsButton.isEnabled = ftsSupported
        engineGroup.check(
            if (ftsSupported &&
                Settings.FILE_INDEX_ENGINE.valueCompat == FileIndex.ENGINE_FTS4) {
                R.id.engineFts
            } else {
                R.id.engineLike
            }
        )
        engineNote.setText(
            if (ftsSupported) {
                R.string.file_index_options_engine_fts_note
            } else {
                R.string.file_index_options_engine_unsupported
            }
        )

        fun apply() {
            val appData = appDataCheck.isChecked
            val level = levels.indexOfFirst { it.first == rootGroup.checkedRadioButtonId }
                .coerceAtLeast(0)
            val engine = if (engineGroup.checkedRadioButtonId == R.id.engineFts) {
                FileIndex.ENGINE_FTS4
            } else {
                FileIndex.ENGINE_LIKE
            }
            Settings.FILE_INDEX_ENABLED.putValue(true)
            Settings.FILE_INDEX_APP_DATA.putValue(appData)
            Settings.FILE_INDEX_ROOT_LEVEL.putValue(level)
            AppExecutors.io.execute {
                if (addStorageRoot) {
                    FileIndex.addRoot(Environment.getExternalStorageDirectory().path)
                }
                FileIndex.applyEngine(engine)
                FileIndex.applyScope()
                FileIndex.startScan(force = true)
            }
            onDone?.invoke()
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_index_options_title)
            .setView(view)
            .setPositiveButton(
                if (addStorageRoot) {
                    R.string.file_index_options_start
                } else {
                    R.string.file_index_options_apply
                }
            ) { _, _ ->
                val warnings = mutableListOf<Int>()
                if (rootGroup.checkedRadioButtonId == R.id.rootAll &&
                    Settings.FILE_INDEX_ROOT_LEVEL.valueCompat != FileIndex.ROOT_LEVEL_ALL) {
                    warnings += R.string.file_index_options_confirm_all
                }
                if (engineGroup.checkedRadioButtonId == R.id.engineFts &&
                    Settings.FILE_INDEX_ENGINE.valueCompat != FileIndex.ENGINE_FTS4) {
                    warnings += R.string.file_index_options_confirm_fts
                }
                if (warnings.isEmpty()) {
                    apply()
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.file_index_options_confirm_title)
                        .setMessage(warnings.joinToString("\n\n") { activity.getString(it) })
                        .setPositiveButton(R.string.file_index_options_confirm_continue) { _, _ ->
                            apply()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> onDone?.invoke() }
                        .setOnCancelListener { onDone?.invoke() }
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone?.invoke() }
            .setOnCancelListener { onDone?.invoke() }
            .show()
    }
}
