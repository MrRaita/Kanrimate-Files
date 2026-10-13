/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.recent

import androidx.preference.PreferenceManager
import me.zhanghai.android.files.app.application

/**
 * The files opened and folders visited from this app, newest first, for the "Recent" screen. It
 * is kept only on this device in the app's preferences and can be cleared from that screen.
 */
object RecentFiles {
    private const val KEY_FILES = "key_recent_files"
    private const val KEY_FOLDERS = "key_recent_folders"
    private const val MAX_FILES = 60
    private const val MAX_FOLDERS = 25

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(application) }

    class Entry(val path: String, val time: Long)

    @Synchronized
    private fun read(key: String): List<Entry> =
        (prefs.getString(key, null) ?: "").split('\n').mapNotNull {
            val tab = it.indexOf('\t')
            if (tab <= 0) {
                null
            } else {
                it.substring(0, tab).toLongOrNull()?.let { time ->
                    Entry(it.substring(tab + 1), time)
                }
            }
        }

    @Synchronized
    private fun add(key: String, path: String, max: Int) {
        if (path.isEmpty() || path.contains('\n')) {
            return
        }
        val entries = read(key).filter { it.path != path }.toMutableList()
        entries.add(0, Entry(path, System.currentTimeMillis()))
        write(key, entries.take(max))
    }

    private fun write(key: String, entries: List<Entry>) {
        prefs.edit().putString(key, entries.joinToString("\n") { "${it.time}\t${it.path}" })
            .apply()
    }

    fun addFile(path: String) = add(KEY_FILES, path, MAX_FILES)

    fun addFolder(path: String) = add(KEY_FOLDERS, path, MAX_FOLDERS)

    val files: List<Entry>
        get() = read(KEY_FILES)

    val folders: List<Entry>
        get() = read(KEY_FOLDERS)

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_FILES).remove(KEY_FOLDERS).apply()
    }
}
