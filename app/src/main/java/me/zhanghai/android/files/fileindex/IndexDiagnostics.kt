/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.ShizukuFileServiceLauncher
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Read-only measurements to decide, with real numbers from this device, how the file name index
 * should evolve: what the device supports, how fast a scan is, and how LIKE compares with a
 * trigram-based FTS4 table. It uses a throwaway database in the cache directory and never touches
 * the real index.
 */
object IndexDiagnostics {
    @Volatile
    var cancelRequested = false

    fun capabilities(): List<String> {
        val lines = mutableListOf<String>()
        lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}"
        val memory = Runtime.getRuntime()
        lines += "App heap limit: ${memory.maxMemory() / (1024 * 1024)} MB"
        val db = SQLiteDatabase.create(null)
        try {
            lines += "SQLite: ${scalar(db, "SELECT sqlite_version()")}"
            lines += "FTS4: ${probe(db, "CREATE VIRTUAL TABLE temp.p4 USING fts4(x)")}"
            lines += "FTS5: ${probe(db, "CREATE VIRTUAL TABLE temp.p5 USING fts5(x)")} (not used)"
        } finally {
            db.close()
        }
        lines += "Shizuku: ${shizukuState()}"
        lines += "Root (su binary): " +
            if (LibSuFileServiceLauncher.isSuAvailable()) "found" else "not found"
        return lines
    }

    private fun shizukuState(): String =
        try {
            when {
                !ShizukuFileServiceLauncher.isAvailable() -> "not running"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    "running, permission granted"
                else -> "running, permission not granted"
            }
        } catch (e: Throwable) {
            "unavailable"
        }

    private fun probe(db: SQLiteDatabase, sql: String): String =
        try {
            db.execSQL(sql)
            "supported"
        } catch (e: Exception) {
            "not supported"
        }

    private fun scalar(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else "?" }

    /** Splits [fold] into distinct 3-character pieces; short names stay whole. */
    fun trigrams(fold: String): String {
        if (fold.codePointCount(0, fold.length) <= 3) {
            return fold.replace(' ', '_')
        }
        val set = LinkedHashSet<String>()
        val points = fold.codePoints().toArray()
        for (i in 0..points.size - 3) {
            set += String(points, i, 3).replace(' ', '_')
        }
        return set.joinToString(" ")
    }

    /**
     * Walks up to [maxEntries] names below [root], stores them in a temporary database both as a
     * plain table (for LIKE) and as a trigram FTS4 table, and reports timings through [log].
     */
    fun runBenchmark(root: File, maxEntries: Int, log: (String) -> Unit) {
        cancelRequested = false
        log("Folder: ${root.path}")
        log("Walking (up to $maxEntries entries)...")
        val paths = ArrayList<String>()
        val folds = ArrayList<String>()
        val walkStart = SystemClock.elapsedRealtime()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && paths.size < maxEntries && !cancelRequested) {
            val directory = stack.removeLast()
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (paths.size >= maxEntries) {
                    break
                }
                paths += child.path
                folds += FileIndex.fold(child.name)
                if (child.isDirectory && !child.path.endsWith("/Android/data") &&
                    !child.path.endsWith("/Android/obb")) {
                    stack.addLast(child)
                }
            }
        }
        val walkMillis = SystemClock.elapsedRealtime() - walkStart
        val count = paths.size
        log("Walk: $count entries in $walkMillis ms (${rate(count, walkMillis)} entries/s)")
        if (count == 0 || cancelRequested) {
            return
        }

        val file = File.createTempFile("index_bench", ".db", application.cacheDir)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE e (path TEXT PRIMARY KEY NOT NULL, fold TEXT NOT NULL) WITHOUT ROWID")
            val likeStart = SystemClock.elapsedRealtime()
            db.beginTransaction()
            try {
                val statement = db.compileStatement("INSERT OR REPLACE INTO e(path, fold) VALUES(?, ?)")
                for (i in 0 until count) {
                    statement.clearBindings()
                    statement.bindString(1, paths[i])
                    statement.bindString(2, folds[i])
                    statement.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val likeInsertMillis = SystemClock.elapsedRealtime() - likeStart
            log("Plain table: insert $likeInsertMillis ms, file ${file.length() / 1024} KB")

            val queries = pickQueries(folds)
            for (query in queries) {
                if (cancelRequested) {
                    return
                }
                var matches = 0
                val times = LongArray(3)
                for (round in 0 until 3) {
                    val start = SystemClock.elapsedRealtime()
                    matches = db.rawQuery(
                        "SELECT COUNT(*) FROM e WHERE fold LIKE ? ESCAPE '\\'",
                        arrayOf("%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                    times[round] = SystemClock.elapsedRealtime() - start
                }
                log("LIKE '$query': $matches matches, ${times.sorted()[1]} ms (median of 3)")
            }

            val fts4 = probe(db, "CREATE VIRTUAL TABLE t USING fts4(tri)")
            if (fts4 != "supported") {
                log("FTS4 trigram: skipped ($fts4)")
                return
            }
            val ftsStart = SystemClock.elapsedRealtime()
            val before = file.length()
            db.beginTransaction()
            try {
                val statement = db.compileStatement("INSERT INTO t(docid, tri) VALUES(?, ?)")
                for (i in 0 until count) {
                    if (cancelRequested) {
                        return
                    }
                    statement.clearBindings()
                    statement.bindLong(1, (i + 1).toLong())
                    statement.bindString(2, trigrams(folds[i]))
                    statement.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val ftsMillis = SystemClock.elapsedRealtime() - ftsStart
            log("FTS4 trigram: build $ftsMillis ms, adds ${(file.length() - before) / 1024} KB")
            for (query in queries) {
                if (query.codePointCount(0, query.length) < 3) {
                    log("FTS4 '$query': needs 3+ characters (LIKE fallback)")
                    continue
                }
                var matches = 0
                val times = LongArray(3)
                for (round in 0 until 3) {
                    val start = SystemClock.elapsedRealtime()
                    matches = db.rawQuery(
                        "SELECT COUNT(*) FROM t WHERE t MATCH ?", arrayOf(trigrams(query))
                    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                    times[round] = SystemClock.elapsedRealtime() - start
                }
                log("FTS4 '$query': $matches candidates, ${times.sorted()[1]} ms (median of 3)")
            }
        } finally {
            db.close()
            file.delete()
            log("Done. Temporary database deleted.")
        }
    }

    private fun rate(count: Int, millis: Long): Long =
        if (millis <= 0) count.toLong() else count * 1000L / millis

    /** Real substrings from the data so the queries actually match something. */
    private fun pickQueries(folds: List<String>): List<String> {
        val result = mutableListOf<String>()
        val step = (folds.size / 4).coerceAtLeast(1)
        var i = step / 2
        while (i < folds.size && result.size < 3) {
            val name = folds[i]
            if (name.length >= 6) {
                result += name.substring(1, 4)
                if (result.size < 3) {
                    result += name.substring(0, 6)
                }
            }
            i += step
        }
        result += "zzqxj"
        return result.distinct().take(5)
    }
}
