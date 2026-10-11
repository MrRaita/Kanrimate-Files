/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path

/**
 * Where to go back to after opening a folder from search results.
 *
 * @param searchRoot the directory the search was run in
 * @param query the search query
 * @param enteredPath the folder that was opened from the results
 */
class SearchReturn(val searchRoot: Path, val query: String, val enteredPath: Path) {
    /** The last directory seen while this return point was active. */
    var lastPath: Path? = null

    /** How many directory changes happened since the folder was opened from the results. */
    var changes = 0
}
