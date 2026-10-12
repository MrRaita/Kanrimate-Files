/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.database.sqlite.SQLiteStatement
import java8.nio.file.AccessDeniedException
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.ShizukuFileServiceLauncher
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

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

    private const val PHASE_MAX_MILLIS = 60_000L
    private const val PHASE_MAX_ENTRIES = 300_000

    /** A temporary database that the scan phases write into, so nothing is kept in memory. */
    private class BenchDb {
        val file: File = File.createTempFile("index_bench", ".db", application.cacheDir)
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        private val insert: SQLiteStatement
        private var pending = 0
        var count = 0
            private set

        init {
            db.execSQL("CREATE TABLE e (path TEXT PRIMARY KEY NOT NULL, fold TEXT NOT NULL) WITHOUT ROWID")
            insert = db.compileStatement("INSERT OR REPLACE INTO e(path, fold) VALUES(?, ?)")
            db.beginTransaction()
        }

        fun add(path: String, fold: String) {
            insert.clearBindings()
            insert.bindString(1, path)
            insert.bindString(2, fold)
            insert.executeInsert()
            count++
            if (++pending >= 20_000) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                pending = 0
            }
        }

        fun add(path: String) = add(path, FileIndex.fold(path.substringAfterLast('/')))

        fun finish() {
            if (db.inTransaction()) {
                db.setTransactionSuccessful()
                db.endTransaction()
            }
        }

        fun close() {
            try {
                if (db.inTransaction()) {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                // Ignore.
            }
            db.close()
            file.delete()
        }
    }

    private class WalkStats {
        var entries = 0
        var denied = 0
        var errors = 0
        var millis = 0L
    }

    private fun walkWithFile(root: File, stats: WalkStats, bench: BenchDb) {
        val start = SystemClock.elapsedRealtime()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && !cancelRequested) {
            val directory = stack.removeLast()
            val children = directory.listFiles()
            if (children == null) {
                stats.denied++
                continue
            }
            for (child in children) {
                stats.entries++
                bench.add(child.path, FileIndex.fold(child.name))
                if (child.isDirectory && !child.path.endsWith("/Android/data") &&
                    !child.path.endsWith("/Android/obb")) {
                    stack.addLast(child)
                }
            }
        }
        stats.millis = SystemClock.elapsedRealtime() - start
    }

    /** Walks through the app's own file layer, which applies the configured root strategy. */
    private fun walkWithPathApi(rootPath: String, stats: WalkStats, bench: BenchDb) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + PHASE_MAX_MILLIS
        val stack = ArrayDeque<Path>()
        stack.addLast(Paths.get(rootPath))
        while (stack.isNotEmpty() && !cancelRequested && stats.entries < PHASE_MAX_ENTRIES &&
            SystemClock.elapsedRealtime() < deadline) {
            val directory = stack.removeLast()
            try {
                Files.newDirectoryStream(directory).use { stream ->
                    for (child in stream) {
                        stats.entries++
                        bench.add(child.toString())
                        val isDirectory = try {
                            Files.readAttributes(
                                child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                            ).isDirectory
                        } catch (e: IOException) {
                            false
                        }
                        if (isDirectory) {
                            stack.addLast(child)
                        }
                        if (stats.entries >= PHASE_MAX_ENTRIES || cancelRequested) {
                            break
                        }
                    }
                }
            } catch (e: AccessDeniedException) {
                stats.denied++
            } catch (e: Exception) {
                stats.errors++
            }
        }
        stats.millis = SystemClock.elapsedRealtime() - start
    }

    /** Streams `find` output run through su, which is the fastest way to list a tree as root. */
    private fun walkWithSuFind(directories: List<String>, stats: WalkStats, bench: BenchDb) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + PHASE_MAX_MILLIS
        val command = "find -H " + directories.joinToString(" ") { "'$it'" } + " 2>/dev/null"
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        } catch (e: IOException) {
            stats.errors++
            return
        }
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (!cancelRequested && stats.entries < PHASE_MAX_ENTRIES &&
                    SystemClock.elapsedRealtime() < deadline) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        continue
                    }
                    stats.entries++
                    bench.add(line)
                }
            }
        } finally {
            // Closing the pipe makes find stop at its next write.
            process.destroy()
        }
        stats.millis = SystemClock.elapsedRealtime() - start
    }

    private fun logWalk(label: String, stats: WalkStats, log: (String) -> Unit) {
        log(
            "$label: ${stats.entries} entries in ${stats.millis} ms " +
                "(${rate(stats.entries, stats.millis)} entries/s)" +
                (if (stats.denied > 0) ", ${stats.denied} folders denied" else "") +
                (if (stats.errors > 0) ", ${stats.errors} errors" else "") +
                (if (stats.millis >= PHASE_MAX_MILLIS || stats.entries >= PHASE_MAX_ENTRIES) {
                    " [stopped at the test limit]"
                } else {
                    ""
                })
        )
    }

    /**
     * Scans the real device in three steps and then measures LIKE and trigram FTS4 on what it
     * found: 1) plain storage, 2) Android/data and Android/obb through the app's root/Shizuku
     * layer, 3) the safe root folders (module and log folders, app data, app code).
     */
    fun runFullBenchmark(log: (String) -> Unit) {
        cancelRequested = false
        val bench = BenchDb()
        try {
            val storage = android.os.Environment.getExternalStorageDirectory()

            log("Step 1: storage ${storage.path}")
            val storageStats = WalkStats()
            walkWithFile(storage, storageStats, bench)
            logWalk("Step 1", storageStats, log)
            if (cancelRequested) {
                return
            }

            log("Step 2: Android/data and Android/obb (root strategy: ${Settings.ROOT_STRATEGY.valueCompat})")
            for (name in listOf("data", "obb")) {
                val directory = File(storage, "Android/$name")
                val plain = directory.listFiles()
                log("  plain access to Android/$name: " + if (plain == null) "denied" else "allowed")
                val stats = WalkStats()
                walkWithPathApi(directory.path, stats, bench)
                logWalk("  Android/$name through the file layer", stats, log)
                if (cancelRequested) {
                    return
                }
            }

            log("Step 3: root folders (safe list)")
            if (!LibSuFileServiceLauncher.isSuAvailable()) {
                log("  su not found, skipped")
            } else {
                val safe = listOf("/data/adb", "/data/data", "/data/app", "/data/local")
                val findStats = WalkStats()
                walkWithSuFind(safe, findStats, bench)
                logWalk("  su find ${safe.joinToString(" ")}", findStats, log)
                if (findStats.entries == 0) {
                    log("  (no output: root permission may have been refused)")
                }
                if (cancelRequested) {
                    return
                }
                val sample = WalkStats()
                walkWithPathApi("/data/adb", sample, bench)
                logWalk("  /data/adb through the file layer", sample, log)
            }
            if (cancelRequested) {
                return
            }
            bench.finish()
            log("Total: ${bench.count} entries collected, temporary file ${bench.file.length() / 1024} KB")
            benchmarkDatabase(bench, log)
        } finally {
            bench.close()
            log("Done. Temporary database deleted.")
        }
    }

    /** Words for synthetic names, mixed English and Turkish like a real storage. */
    private val WORDS = arrayOf(
        "photo", "image", "report", "invoice", "music", "video", "backup", "chapter", "track",
        "screenshot", "download", "document", "project", "notes", "cache", "thumbnail", "config",
        "module", "update", "release", "fatura", "rapor", "belge", "resim", "muzik", "ders",
        "odev", "sunum", "tablo", "yedek", "ayarlar", "indirilen", "kamera", "ekran", "dosya"
    )
    private val EXTENSIONS = arrayOf(".jpg", ".png", ".mp4", ".mp3", ".pdf", ".txt", ".zip", ".apk", ".json", "")

    private fun mix(i: Int): Long {
        var x = i.toLong() * -7046029254386353131L
        x = x xor (x ushr 29)
        x *= -0x40a7b892e31b1a47L
        return x xor (x ushr 32)
    }

    private fun syntheticName(i: Int): String {
        val h = mix(i)
        val a = WORDS[(h and 0xFF).toInt() % WORDS.size]
        val b = WORDS[((h ushr 8) and 0xFF).toInt() % WORDS.size]
        val e = EXTENSIONS[((h ushr 16) and 0xFF).toInt() % EXTENSIONS.size]
        return "${a}_${b}_$i$e"
    }

    private fun syntheticPath(i: Int): String {
        val h = mix(i / 40)
        val dir = WORDS[(h and 0xFF).toInt() % WORDS.size]
        return "/storage/emulated/0/$dir/${i / 40}/${syntheticName(i)}"
    }

    /** Same measurements on generated names, so very large sizes can be tried. */
    fun runSyntheticBenchmark(count: Int, log: (String) -> Unit) {
        cancelRequested = false
        log("Synthetic data: $count generated names")
        val bench = BenchDb()
        try {
            for (i in 0 until count) {
                if (cancelRequested) {
                    return
                }
                bench.add(syntheticPath(i), FileIndex.fold(syntheticName(i)))
            }
            bench.finish()
            benchmarkDatabase(bench, log)
        } finally {
            bench.close()
            log("Done. Temporary database deleted.")
        }
    }

    private fun benchmarkDatabase(bench: BenchDb, log: (String) -> Unit) {
        val db = bench.db
        val count = bench.count
        if (count == 0) {
            log("Nothing collected.")
            return
        }
        log("Plain table: $count rows, file ${bench.file.length() / 1024} KB")
        val queries = pickQueries(db, count)
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
        val before = bench.file.length()
        db.beginTransaction()
        try {
            val statement = db.compileStatement("INSERT INTO t(docid, tri) VALUES(?, ?)")
            var docid = 0L
            db.rawQuery("SELECT fold FROM e", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cancelRequested) {
                        return
                    }
                    statement.clearBindings()
                    statement.bindLong(1, ++docid)
                    statement.bindString(2, trigrams(cursor.getString(0)))
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val ftsMillis = SystemClock.elapsedRealtime() - ftsStart
        log("FTS4 trigram: build $ftsMillis ms, adds ${(bench.file.length() - before) / 1024} KB")
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
    }

    private fun rate(count: Int, millis: Long): Long =
        if (millis <= 0) count.toLong() else count * 1000L / millis

    /** Real substrings from the data so the queries actually match something. */
    private fun pickQueries(db: SQLiteDatabase, count: Int): List<String> {
        val result = mutableListOf<String>()
        val step = (count / 6).coerceAtLeast(1)
        var i = step / 2
        while (i < count && result.size < 3) {
            val name = db.rawQuery("SELECT fold FROM e LIMIT 1 OFFSET ?", arrayOf(i.toString()))
                .use { if (it.moveToFirst()) it.getString(0) else "" }
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
