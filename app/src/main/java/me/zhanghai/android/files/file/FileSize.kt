/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.content.Context
import android.text.format.Formatter
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.valueCompat
import java.util.Locale

@JvmInline
value class FileSize(val value: Long) {

    /* @see android.text.format.Formatter#formatBytes(Resources, long, int) */
    val isHumanReadableInBytes: Boolean
        get() = value <= (if (Settings.FILE_SIZE_BINARY.valueCompat) 1023 else 900)

    fun formatInBytes(context: Context): String =
        context.getQuantityString(R.plurals.size_in_bytes_format, value.toInt(), value)

    fun formatHumanReadable(context: Context): String =
        if (Settings.FILE_SIZE_BINARY.valueCompat) {
            formatBinary(context)
        } else {
            Formatter.formatFileSize(context, value)
        }

    /** Formats with 1024-based units: B, KiB, MiB, GiB, TiB, PiB. */
    private fun formatBinary(context: Context): String {
        if (value < 1024) {
            return formatInBytes(context)
        }
        var scaled = value.toDouble()
        var unitIndex = -1
        while (scaled >= 1024 && unitIndex < BINARY_UNITS.size - 1) {
            scaled /= 1024
            unitIndex++
        }
        val fractionDigits = when {
            scaled < 10 -> 2
            scaled < 100 -> 1
            else -> 0
        }
        return String.format(Locale.getDefault(), "%.${fractionDigits}f %s", scaled,
            BINARY_UNITS[unitIndex])
    }

    private companion object {
        val BINARY_UNITS = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
    }
}

fun Long.asFileSize(): FileSize = FileSize(this)
