/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.RootStrategy
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
                    // Also ask how the index should be kept up to date.
                    showAutoDialog(activity, onDone)
                } else {
                    onDone?.invoke()
                }
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
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showOptionsDialog(activity, addStorageRoot, access, onDone)
                }
            }
        }
    }

    /** A row laid out like a preference in the settings: title, summary and a switch. */
    private class OptionRow(val view: View) {
        private val title = view.findViewById<TextView>(R.id.rowTitle)
        private val summary = view.findViewById<TextView>(R.id.rowSummary)
        private val switch = view.findViewById<SwitchMaterial>(R.id.rowSwitch)
        var onChanged: (() -> Unit)? = null

        init {
            view.setOnClickListener {
                if (view.isEnabled && switch.visibility == View.VISIBLE) {
                    switch.toggle()
                }
            }
            switch.setOnCheckedChangeListener { _, _ -> onChanged?.invoke() }
        }

        var isChecked: Boolean
            get() = switch.isChecked
            set(value) {
                switch.isChecked = value
            }

        var isEnabled: Boolean
            get() = view.isEnabled
            set(value) {
                view.isEnabled = value
                switch.isEnabled = value
                view.alpha = if (value) 1f else 0.5f
            }

        fun setTitle(text: CharSequence) {
            title.text = text
        }

        fun setSummary(text: CharSequence?) {
            summary.text = text
            summary.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        /** Makes this a plain row without a switch (a choice that opens its own dialog). */
        fun hideSwitch(onClick: () -> Unit) {
            switch.visibility = View.GONE
            view.setOnClickListener { onClick() }
        }
    }

    private fun showOptionsDialog(
        activity: AppCompatActivity,
        addStorageRoot: Boolean,
        access: Access,
        onDone: (() -> Unit)?
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.file_index_options_dialog, null)
        val appData = OptionRow(view.findViewById(R.id.appDataRow))
        val root = OptionRow(view.findViewById(R.id.rootRow))
        val rootOptions = view.findViewById<View>(R.id.rootOptions)
        val dirs = OptionRow(view.findViewById(R.id.rootDirsRow))
        val user = OptionRow(view.findViewById(R.id.rootUserRow))
        val all = OptionRow(view.findViewById(R.id.rootAllRow))

        // Shizuku / app data.
        val appDataPossible = access.shizuku || access.root
        appData.setTitle(activity.getString(R.string.file_index_options_app_data))
        appData.isEnabled = appDataPossible
        appData.isChecked = appDataPossible && Settings.FILE_INDEX_APP_DATA.valueCompat
        var appDataNoteText = activity.getString(
            when {
                access.shizuku -> R.string.file_index_options_app_data_shizuku_ready
                access.root -> R.string.file_index_options_app_data_root_only
                else -> R.string.file_index_options_app_data_unavailable
            }
        )
        if (Settings.ROOT_STRATEGY.valueCompat == RootStrategy.NEVER) {
            // Applying these options switches the root setting, so say so before it happens.
            appDataNoteText += "\n" + activity.getString(R.string.file_index_options_strategy_note)
        }
        appData.setSummary(appDataNoteText)

        // Root mode: one switch that opens the three independent choices below it.
        root.setTitle(activity.getString(R.string.file_index_options_root_mode))
        root.setSummary(
            activity.getString(
                if (access.root) {
                    R.string.file_index_options_root_available
                } else {
                    R.string.file_index_options_root_unavailable
                }
            )
        )
        dirs.setTitle(activity.getString(R.string.file_index_options_root_dirs))
        dirs.setSummary(activity.getString(R.string.file_index_options_root_dirs_description))
        user.setTitle(activity.getString(R.string.file_index_options_root_user))
        user.setSummary(activity.getString(R.string.file_index_options_root_user_description))
        all.setTitle(activity.getString(R.string.file_index_options_root_all))
        all.setSummary(activity.getString(R.string.file_index_options_root_all_description))
        val mask = if (access.root) Settings.FILE_INDEX_ROOT_MASK.valueCompat else 0
        root.isEnabled = access.root
        root.isChecked = mask != 0
        dirs.isChecked = mask and FileIndex.ROOT_DIRS != 0
        user.isChecked = mask and FileIndex.ROOT_USER_DATA != 0
        all.isChecked = mask and FileIndex.ROOT_ALL != 0

        // "All folders" includes the other two, so they are shown on and greyed out.
        fun updateRootViews() {
            rootOptions.visibility = if (root.isChecked) View.VISIBLE else View.GONE
            val allOn = all.isChecked
            dirs.isEnabled = !allOn
            user.isEnabled = !allOn
            if (allOn) {
                dirs.isChecked = true
                user.isChecked = true
            }
        }
        updateRootViews()
        root.onChanged = {
            if (root.isChecked && !dirs.isChecked && !user.isChecked && !all.isChecked) {
                dirs.isChecked = true
            }
            updateRootViews()
        }
        all.onChanged = { updateRootViews() }

        fun selectedMask(): Int {
            if (!root.isChecked) {
                return 0
            }
            return when {
                all.isChecked -> FileIndex.ROOT_ALL
                else ->
                    (if (dirs.isChecked) FileIndex.ROOT_DIRS else 0) or
                        (if (user.isChecked) FileIndex.ROOT_USER_DATA else 0)
            }
        }

        fun apply() {
            val newMask = selectedMask()
            Settings.FILE_INDEX_ENABLED.putValue(true)
            Settings.FILE_INDEX_APP_DATA.putValue(appData.isChecked)
            Settings.FILE_INDEX_ROOT_MASK.putValue(newMask)
            AppExecutors.io.execute {
                if (addStorageRoot) {
                    FileIndex.addRoot(Environment.getExternalStorageDirectory().path)
                }
                FileIndex.applyScope()
                FileIndex.startScan(force = true)
            }
            if (addStorageRoot) {
                // First run: also ask how the index should be kept up to date.
                showAutoDialog(activity, onDone)
            } else {
                onDone?.invoke()
            }
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
                val newlyAll = selectedMask() == FileIndex.ROOT_ALL &&
                    Settings.FILE_INDEX_ROOT_MASK.valueCompat != FileIndex.ROOT_ALL
                if (!newlyAll) {
                    apply()
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.file_index_options_confirm_title)
                        .setMessage(R.string.file_index_options_confirm_all)
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

    /** The choices for how often the index refreshes itself, in hours; 0 is off. */
    private val AUTO_INTERVAL_VALUES = listOf("0", "1", "2", "4", "8", "12", "24")

    /**
     * Asks when the index should refresh by itself. Shown after the first setup; the same three
     * settings can be changed later in the settings screen.
     */
    fun showAutoDialog(activity: AppCompatActivity, onDone: (() -> Unit)? = null) {
        val view = LayoutInflater.from(activity).inflate(R.layout.file_index_auto_dialog, null)
        val open = OptionRow(view.findViewById(R.id.autoOpenRow))
        val interval = OptionRow(view.findViewById(R.id.autoIntervalRow))
        val charging = OptionRow(view.findViewById(R.id.autoChargingRow))
        val entries = activity.resources.getStringArray(R.array.settings_file_index_auto_interval_entries)

        open.setTitle(activity.getString(R.string.settings_file_index_auto_open_title))
        open.setSummary(activity.getString(R.string.settings_file_index_auto_open_summary))
        open.isChecked = Settings.FILE_INDEX_AUTO_OPEN.valueCompat

        var selectedInterval = AUTO_INTERVAL_VALUES.indexOf(
            Settings.FILE_INDEX_AUTO_INTERVAL.valueCompat
        ).coerceAtLeast(0)
        interval.setTitle(activity.getString(R.string.settings_file_index_auto_interval_title))
        interval.setSummary(entries[selectedInterval])
        interval.hideSwitch {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_file_index_auto_interval_title)
                .setSingleChoiceItems(entries, selectedInterval) { dialog, which ->
                    selectedInterval = which
                    interval.setSummary(entries[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        charging.setTitle(activity.getString(R.string.settings_file_index_auto_charging_title))
        charging.setSummary(activity.getString(R.string.settings_file_index_auto_charging_summary))
        charging.isChecked = Settings.FILE_INDEX_AUTO_CHARGING.valueCompat

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_index_auto_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.FILE_INDEX_AUTO_OPEN.putValue(open.isChecked)
                Settings.FILE_INDEX_AUTO_INTERVAL.putValue(AUTO_INTERVAL_VALUES[selectedInterval])
                Settings.FILE_INDEX_AUTO_CHARGING.putValue(charging.isChecked)
                AppExecutors.io.execute { FileIndexScheduler.update() }
                onDone?.invoke()
            }
            .setNegativeButton(R.string.file_index_prompt_not_now) { _, _ -> onDone?.invoke() }
            .setOnCancelListener { onDone?.invoke() }
            .show()
    }
}
