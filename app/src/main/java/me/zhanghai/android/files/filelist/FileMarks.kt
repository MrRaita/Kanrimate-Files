/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java8.nio.file.Path
import me.zhanghai.android.files.app.application

/**
 * Two small, app-level lists of paths: items pinned to the top of their folder, and items hidden
 * from the file list. Nothing is changed on disk (no `.nomedia`, no renames), the lists only live in
 * this app's preferences.
 */
object FileMarks {
    private const val KEY_PINNED = "key_file_pinned_paths"
    private const val KEY_HIDDEN = "key_file_hidden_paths"

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(application) }

    private var pinned: Set<String>? = null
    private var hidden: Set<String>? = null

    /** Changes whenever either list changes, so lists can be re-sorted and re-filtered. */
    val changed = MutableLiveData(0L)

    private fun load(key: String): Set<String> =
        HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())

    private fun pinnedSet(): Set<String> = pinned ?: load(KEY_PINNED).also { pinned = it }

    private fun hiddenSet(): Set<String> = hidden ?: load(KEY_HIDDEN).also { hidden = it }

    fun isPinned(path: Path): Boolean = pinnedSet().let { it.isNotEmpty() && path.toString() in it }

    /** Hidden itself. */
    fun isHidden(path: Path): Boolean = hiddenSet().let { it.isNotEmpty() && path.toString() in it }

    /** Hidden itself or below a hidden folder. */
    fun isHiddenDeep(path: Path): Boolean {
        val set = hiddenSet()
        if (set.isEmpty()) {
            return false
        }
        var current = path.toString()
        while (true) {
            if (current in set) {
                return true
            }
            val index = current.lastIndexOf('/')
            if (index <= 0) {
                return false
            }
            current = current.substring(0, index)
        }
    }

    val hiddenPaths: List<String>
        get() = hiddenSet().sorted()

    fun setPinned(paths: Collection<Path>, value: Boolean) {
        update(KEY_PINNED, pinnedSet(), paths.map { it.toString() }, value) { pinned = it }
    }

    fun setHidden(paths: Collection<Path>, value: Boolean) {
        update(KEY_HIDDEN, hiddenSet(), paths.map { it.toString() }, value) { hidden = it }
    }

    fun unhide(paths: Collection<String>) {
        update(KEY_HIDDEN, hiddenSet(), paths, false) { hidden = it }
    }

    private fun update(
        key: String,
        old: Set<String>,
        paths: Collection<String>,
        value: Boolean,
        store: (Set<String>) -> Unit
    ) {
        val new = HashSet(old)
        if (value) new.addAll(paths) else new.removeAll(paths.toSet())
        if (new == old) {
            return
        }
        store(new)
        prefs.edit().putStringSet(key, new).apply()
        changed.postValue(System.currentTimeMillis())
    }
}
