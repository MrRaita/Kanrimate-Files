/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

class SearchState(
    val isSearching: Boolean,
    val query: String,
    /** False when the user asked for a normal search that ignores the file name index. */
    val useIndex: Boolean = true
)
