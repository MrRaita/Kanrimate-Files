/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import me.zhanghai.android.files.app.application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain text log of what the index does, kept in the app's private storage so that a user can
 * copy it from the settings and hand it over when something looks wrong. It records folder paths
 * and counts, never file contents, and never the text of a search.
 */
object IndexLog {
    private const val MAX_BYTES = 400L * 1024
    private const val READ_LIMIT_BYTES = 120 * 1024

    private val lock = Any()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun file(): File = File(application.filesDir, "file_index.log")

    fun log(message: String) {
        try {
            synchronized(lock) {
                val file = file()
                if (file.length() > MAX_BYTES) {
                    val old = File(file.path + ".1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(
                    format.format(Date()) + " [" + Thread.currentThread().name + "] " + message + "\n"
                )
            }
        } catch (e: Exception) {
            // Logging must never break the index.
        }
    }

    fun log(message: String, error: Throwable) {
        log(message + ": " + error.javaClass.simpleName + ": " + error.message)
    }

    /** The newest part of the log, oldest line first. */
    fun read(): String =
        try {
            synchronized(lock) {
                val file = file()
                if (!file.exists()) {
                    ""
                } else {
                    val length = file.length()
                    java.io.RandomAccessFile(file, "r").use { input ->
                        val start = (length - READ_LIMIT_BYTES).coerceAtLeast(0)
                        input.seek(start)
                        val bytes = ByteArray((length - start).toInt())
                        input.readFully(bytes)
                        val text = String(bytes, Charsets.UTF_8)
                        if (start > 0) text.substringAfter('\n') else text
                    }
                }
            }
        } catch (e: Exception) {
            "Could not read the log: $e"
        }

    fun clear() {
        synchronized(lock) {
            file().delete()
            File(file().path + ".1").delete()
        }
    }
}
