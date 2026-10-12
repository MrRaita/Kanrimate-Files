/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.fileindex.FileIndex
import me.zhanghai.android.files.fileindex.FileIndexPrompt
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.AppExecutors
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.R
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference
    private lateinit var fileIndexFoldersPreference: Preference

    private val addIndexFolderLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract()) { path ->
            if (path != null && path.isLinuxPath) {
                FileIndex.addRoot(path.toFile().path)
                FileIndex.startScan(force = true)
                updateFileIndexSummary()
            }
        }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        setUpFileIndexPreferences()
        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }
    }

    private fun setUpFileIndexPreferences() {
        fileIndexFoldersPreference =
            preferenceScreen.findPreference(getString(R.string.pref_key_file_index_folders))!!
        fileIndexFoldersPreference.setOnPreferenceClickListener {
            showFileIndexFoldersDialog()
            true
        }
        preferenceScreen.findPreference<Preference>(
            getString(R.string.pref_key_file_index_rebuild)
        )!!.setOnPreferenceClickListener {
            FileIndex.startScan(force = true)
            requireContext().showToast(R.string.settings_file_index_rebuild_started)
            true
        }
        preferenceScreen.findPreference<Preference>(
            getString(R.string.pref_key_file_index_enabled)
        )!!.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                if (FileIndex.getRoots().isEmpty()) {
                    FileIndexPrompt.showDialog(requireActivity() as AppCompatActivity) {
                        updateFileIndexSummary()
                    }
                } else {
                    FileIndex.startScan(force = true)
                }
            } else {
                // Free the space and forget the names when the user turns the feature off.
                FileIndex.cancelScan()
                AppExecutors.io.execute { FileIndex.clear() }
            }
            true
        }
        updateFileIndexSummary()
    }

    private fun updateFileIndexSummary() {
        AppExecutors.io.execute {
            val folders = FileIndex.getRoots().size
            val names = FileIndex.getEntryCount()
            activity?.runOnUiThread {
                if (isAdded) {
                    fileIndexFoldersPreference.summary =
                        getString(
                            R.string.settings_file_index_folders_summary_format, folders,
                            names.toInt()
                        )
                }
            }
        }
    }

    private fun showFileIndexFoldersDialog() {
        AppExecutors.io.execute {
            val roots = FileIndex.getRoots()
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }
                val checked = BooleanArray(roots.size) { true }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_file_index_folders_title)
                    .setMultiChoiceItems(roots.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val removed = roots.filterIndexed { index, _ -> !checked[index] }
                        AppExecutors.io.execute {
                            FileIndex.removeRoots(removed)
                            updateFileIndexSummary()
                        }
                    }
                    .setNeutralButton(R.string.settings_file_index_add_folder) { _, _ ->
                        addIndexFolderLauncher.launch(null)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }


    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
