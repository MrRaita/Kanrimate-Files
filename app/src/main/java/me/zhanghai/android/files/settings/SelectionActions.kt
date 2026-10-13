package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import me.zhanghai.android.files.R

/**
 * The actions shown while files are selected in the file list (long press), and where each of them is
 * shown: directly in the toolbar, inside the "⋮" overflow menu, or not at all. The layout is stored as
 * `id:placement,id:placement,...` in [PREF_KEY], in display order.
 */
object SelectionActions {
    const val PREF_KEY = "key_file_list_selection_actions"

    enum class Placement { TOOLBAR, MENU, HIDDEN }

    class Entry(val id: String, val placement: Placement)

    class Info(val id: String, val titleRes: Int, val defaultPlacement: Placement)

    val all: List<Info> = listOf(
        Info("cut", R.string.cut, Placement.TOOLBAR),
        Info("copy", R.string.copy, Placement.TOOLBAR),
        Info("delete", R.string.delete, Placement.TOOLBAR),
        Info("extract", R.string.file_list_select_action_extract, Placement.MENU),
        Info("archive", R.string.file_list_select_action_archive, Placement.MENU),
        Info("share", R.string.share, Placement.MENU),
        Info("select_all", R.string.select_all, Placement.MENU),
        Info("select_inverse", R.string.file_list_select_action_invert, Placement.MENU),
        Info("copy_path", R.string.file_list_action_copy_path, Placement.MENU),
        Info("pin", R.string.file_list_select_action_pin, Placement.MENU),
        Info("hide", R.string.file_list_select_action_hide, Placement.MENU)
    )

    fun info(id: String): Info? = all.firstOrNull { it.id == id }

    fun defaults(): List<Entry> = all.map { Entry(it.id, it.defaultPlacement) }

    /** Unknown ids are dropped; actions missing from the stored value are appended with their default. */
    fun load(prefs: SharedPreferences): List<Entry> {
        val stored = prefs.getString(PREF_KEY, null) ?: return defaults()
        val result = mutableListOf<Entry>()
        for (part in stored.split(',')) {
            val (id, name) = part.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            val info = info(id) ?: continue
            if (result.any { it.id == id }) continue
            val placement = Placement.values().firstOrNull { it.name == name } ?: info.defaultPlacement
            result.add(Entry(id, placement))
        }
        for (info in all) {
            if (result.none { it.id == info.id }) result.add(Entry(info.id, info.defaultPlacement))
        }
        return result
    }

    fun save(prefs: SharedPreferences, entries: List<Entry>) {
        prefs.edit().putString(PREF_KEY, entries.joinToString(",") { "${it.id}:${it.placement.name}" }).apply()
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit().remove(PREF_KEY).apply()
    }
}
