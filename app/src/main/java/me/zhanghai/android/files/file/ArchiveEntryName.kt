/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import java8.nio.file.Path
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Makes names of extracted archive entries valid on shared storage, which rejects
 * `" * : < > ? \ |` and control characters. Only the name written to disk changes, never the
 * archive itself.
 */
object ArchiveEntryName {
    const val MODE_LOOKALIKE = "lookalike"
    const val MODE_UNDERSCORE = "underscore"

    private const val MAX_NAME_BYTES = 255

    private val LOOKALIKES = mapOf(
        '"' to '\uFF02',
        '*' to '\uFF0A',
        ':' to '\uFF1A',
        '<' to '\uFF1C',
        '>' to '\uFF1E',
        '?' to '\uFF1F',
        '\\' to '\uFF3C',
        '|' to '\uFF5C'
    )

    /** Whether names under [target] need to be sanitized. */
    fun isNeeded(target: Path): Boolean {
        if (target.isDocumentPath) {
            return true
        }
        if (!target.isLinuxPath) {
            return false
        }
        val path = target.toString()
        return path.startsWith("/storage/") || path.startsWith("/sdcard")
    }

    fun sanitize(name: String): String {
        if (name == "." || name == "..") {
            return name
        }
        val lookalike = Settings.ARCHIVE_INVALID_CHAR_MODE.valueCompat != MODE_UNDERSCORE
        val builder = StringBuilder(name.length)
        for (char in name) {
            when {
                char < ' ' || char == '\u007F' -> builder.append('_')
                char in LOOKALIKES -> builder.append(if (lookalike) LOOKALIKES.getValue(char) else '_')
                else -> builder.append(char)
            }
        }
        var result = builder.toString()
        if (result.isEmpty()) {
            result = "_"
        }
        return truncate(result)
    }

    /** Keeps the extension while limiting the name to the 255-byte file name limit. */
    private fun truncate(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
            return name
        }
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        var base = name.substring(0, name.length - extension.length)
        while (base.isNotEmpty() &&
            (base + extension).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            // Drop whole code points so we never split a surrogate pair.
            val end = base.offsetByCodePoints(base.length, -1)
            base = base.substring(0, end)
        }
        return base + extension
    }
}
