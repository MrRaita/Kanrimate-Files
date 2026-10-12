/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.BatteryManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.powerManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional persistent index of file names, used to answer searches without walking the file tree.
 *
 * Deliberate limits: only user-chosen folders, a hard cap on entries, no file observers, a single
 * low-priority thread, and no scanning on low battery or in power saving mode (unless the user
 * asks for a rebuild). The scan never deletes the old index until it has finished, so being
 * killed halfway is harmless.
 */
object FileIndex {
    private const val MAX_ENTRIES = 1_000_000
    private const val BATCH_SIZE = 1000
    private const val BATTERY_CHECK_EVERY_BATCHES = 20
    private const val MIN_BATTERY_PERCENT = 20
    private const val REFRESH_AFTER_MILLIS = 12L * 60 * 60 * 1000
    private const val MAX_RESULTS = 5000

    private val scanning = AtomicBoolean(false)

    @Volatile
    private var cancelRequested = false

    val isScanning: Boolean
        get() = scanning.get()

    /** Case folding identical to what `String.contains(ignoreCase = true)` compares. */
    fun fold(string: String): String {
        val builder = StringBuilder(string.length)
        for (char in string) {
            builder.append(char.uppercaseChar().lowercaseChar())
        }
        return builder.toString()
    }

    private fun normalizeRoot(path: String): String {
        var result = path
        if (result == "/sdcard" || result.startsWith("/sdcard/")) {
            result = "/storage/emulated/0" + result.removePrefix("/sdcard")
        }
        return result.trimEnd('/').ifEmpty { "/" }
    }

    private fun database(): SQLiteDatabase = FileIndexDatabase.instance.writableDatabase

    fun getRoots(): List<String> {
        val roots = mutableListOf<String>()
        try {
            database().rawQuery("SELECT path FROM roots ORDER BY path", null).use {
                while (it.moveToNext()) {
                    roots += it.getString(0)
                }
            }
        } catch (e: SQLiteException) {
            e.printStackTrace()
        }
        return roots
    }

    fun addRoot(path: String) {
        val root = normalizeRoot(path)
        try {
            database().execSQL("INSERT OR IGNORE INTO roots(path, scanned_at) VALUES(?, 0)", arrayOf<Any>(root))
        } catch (e: SQLiteException) {
            e.printStackTrace()
        }
    }

    fun removeRoots(paths: Collection<String>) {
        if (paths.isEmpty()) {
            return
        }
        try {
            val db = database()
            db.beginTransaction()
            try {
                for (path in paths) {
                    db.execSQL("DELETE FROM entries WHERE root = ?", arrayOf<Any>(path))
                    db.execSQL("DELETE FROM roots WHERE path = ?", arrayOf<Any>(path))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: SQLiteException) {
            e.printStackTrace()
        }
    }

    fun clear() {
        cancelRequested = true
        try {
            val db = database()
            db.execSQL("DELETE FROM entries")
            db.execSQL("DELETE FROM roots")
        } catch (e: SQLiteException) {
            e.printStackTrace()
        }
    }

    fun getEntryCount(): Long =
        try {
            database().rawQuery("SELECT COUNT(*) FROM entries", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (e: SQLiteException) {
            0L
        }

    /** Starts a scan in the background if the index is enabled and out of date. */
    fun refreshIfStale() {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat) {
            return
        }
        val now = System.currentTimeMillis()
        val stale = try {
            database().rawQuery(
                "SELECT COUNT(*) FROM roots WHERE scanned_at < ?",
                arrayOf((now - REFRESH_AFTER_MILLIS).toString())
            ).use { it.moveToFirst() && it.getInt(0) > 0 }
        } catch (e: SQLiteException) {
            false
        }
        if (stale) {
            startScan(force = false)
        }
    }

    fun cancelScan() {
        cancelRequested = true
    }

    /** @param force ignore the battery and power saving checks (user asked for it) */
    fun startScan(force: Boolean) {
        if (!scanning.compareAndSet(false, true)) {
            return
        }
        cancelRequested = false
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                scanAllRoots(force)
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                scanning.set(false)
            }
        }, "FileIndex.Scan").start()
    }

    private fun canScanNow(force: Boolean): Boolean {
        if (force) {
            return true
        }
        if (powerManager.isPowerSaveMode) {
            return false
        }
        val status = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return true
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged || level < 0 || scale <= 0) {
            return true
        }
        return level * 100 / scale >= MIN_BATTERY_PERCENT
    }

    private fun scanAllRoots(force: Boolean) {
        val db = database()
        if (getRoots().isEmpty()) {
            return
        }
        var total = 0
        // The previous scan's size is the best guess for how much work there is.
        val estimatedTotal = getEntryCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var finishedAll = true
        FileIndexNotification.showProgress(0, estimatedTotal, force = true)
        for (root in getRoots()) {
            if (cancelRequested || !canScanNow(force)) {
                finishedAll = false
                break
            }
            val scanId = System.currentTimeMillis()
            val completed = scanRoot(db, root, scanId, force, MAX_ENTRIES - total) {
                total += it
                FileIndexNotification.showProgress(total, estimatedTotal)
            }
            if (!completed) {
                finishedAll = false
            }
            if (completed) {
                try {
                    db.execSQL("DELETE FROM entries WHERE root = ? AND scan <> ?", arrayOf<Any>(root, scanId))
                    db.execSQL("UPDATE roots SET scanned_at = ? WHERE path = ?", arrayOf<Any>(scanId, root))
                } catch (e: SQLiteException) {
                    e.printStackTrace()
                }
            }
        }
        if (finishedAll) {
            FileIndexNotification.showDone(total)
        } else {
            FileIndexNotification.cancel()
        }
    }

    private fun isSymbolicLink(file: File): Boolean =
        try {
            OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
        } catch (e: ErrnoException) {
            true
        }

    private fun isExcluded(file: File): Boolean {
        val path = file.path
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb")
    }

    /** @return whether the whole root was scanned */
    private fun scanRoot(
        db: SQLiteDatabase,
        root: String,
        scanId: Long,
        force: Boolean,
        budget: Int,
        onInserted: (Int) -> Unit
    ): Boolean {
        val rootFile = File(root)
        if (!rootFile.isDirectory) {
            return false
        }
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO entries(path, fold, root, scan) VALUES(?, ?, ?, ?)"
        )
        val stack = ArrayDeque<File>()
        stack.addLast(rootFile)
        var inserted = 0
        var batches = 0
        var inBatch = 0
        db.beginTransaction()
        try {
            while (stack.isNotEmpty()) {
                if (cancelRequested || Thread.currentThread().isInterrupted) {
                    db.setTransactionSuccessful()
                    return false
                }
                val directory = stack.removeLast()
                val children = directory.listFiles() ?: continue
                for (child in children) {
                    if (inserted >= budget) {
                        db.setTransactionSuccessful()
                        // Hitting the cap means the index is incomplete; keep the old rows.
                        return false
                    }
                    val name = child.name
                    statement.clearBindings()
                    statement.bindString(1, child.path)
                    statement.bindString(2, fold(name))
                    statement.bindString(3, root)
                    statement.bindLong(4, scanId)
                    statement.executeInsert()
                    inserted++
                    inBatch++
                    if (child.isDirectory && !isExcluded(child) && !isSymbolicLink(child)) {
                        stack.addLast(child)
                    }
                    if (inBatch >= BATCH_SIZE) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        onInserted(inBatch)
                        inBatch = 0
                        batches++
                        if (batches % BATTERY_CHECK_EVERY_BATCHES == 0 && !canScanNow(force)) {
                            return false
                        }
                        // Let other work on the database and the CPU through.
                        Thread.sleep(5)
                        db.beginTransaction()
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (e: InterruptedException) {
            return false
        } finally {
            if (db.inTransaction()) {
                db.endTransaction()
            }
            onInserted(inBatch)
        }
        return true
    }

    private fun escapeLike(string: String): String =
        string.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Looks up names containing [query] below [searchRoot].
     *
     * @return the matching paths, or `null` if the index cannot answer this search (disabled,
     *   folder not covered, or any database problem) and the caller should walk the tree
     */
    fun search(searchRoot: String, query: String): List<String>? {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat || query.isBlank()) {
            return null
        }
        val root = normalizeRoot(searchRoot)
        try {
            val db = database()
            var covered = false
            db.rawQuery("SELECT path FROM roots WHERE scanned_at > 0", null).use {
                while (it.moveToNext()) {
                    val indexedRoot = it.getString(0)
                    if (root == indexedRoot || root.startsWith(indexedRoot.trimEnd('/') + "/")) {
                        covered = true
                    }
                }
            }
            if (!covered) {
                return null
            }
            val prefix = root.trimEnd('/')
            // '0' is the character right after '/', so this is exactly "paths below root".
            val lower = "$prefix/"
            val upper = "${prefix}0"
            val results = mutableListOf<String>()
            db.rawQuery(
                "SELECT path FROM entries WHERE path > ? AND path < ? " +
                    "AND fold LIKE ? ESCAPE '\\' ORDER BY fold LIMIT $MAX_RESULTS",
                arrayOf(lower, upper, "%" + escapeLike(fold(query)) + "%")
            ).use {
                while (it.moveToNext()) {
                    results += it.getString(0)
                }
            }
            return results
        } catch (e: SQLiteException) {
            e.printStackTrace()
            return null
        }
    }
}
