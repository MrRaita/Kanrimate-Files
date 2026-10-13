/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java.io.File

/**
 * Finds the `su` executable as an absolute path, so commands are never started through a bare
 * relative name that depends on the PATH variable at the moment of the call (CWE-78/CWE-88).
 */
object SuBinary {
    private val KNOWN_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/product/bin/su",
        "/su/bin/su", "/debug_ramdisk/su"
    )

    /** @return the absolute path of su, or null if there is none the app can run */
    fun find(): String? {
        KNOWN_PATHS.firstOrNull { File(it).canExecute() }?.let { return it }
        // Some root managers (for example through a module) only put su on the PATH.
        val directories = System.getenv("PATH")?.split(':') ?: return null
        for (directory in directories) {
            if (!directory.startsWith("/")) {
                continue
            }
            val file = File(directory, "su")
            if (file.isFile && file.canExecute()) {
                return file.path
            }
        }
        return null
    }
}
