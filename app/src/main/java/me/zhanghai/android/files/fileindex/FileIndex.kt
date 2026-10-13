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
import android.os.Environment
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.powerManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.root.RootStrategy
import java.io.File
import java.io.IOException
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
    private const val MAX_ROOT_ENTRIES = 3_000_000
    private const val BATCH_SIZE = 1000
    private const val BATTERY_CHECK_EVERY_BATCHES = 20
    private const val MIN_BATTERY_PERCENT = 20
    private const val REFRESH_AFTER_MILLIS = 12L * 60 * 60 * 1000
    private const val MAX_RESULTS = 5000

    private const val STALE_AFTER_MILLIS = 30L * 60 * 1000

    /** Root scope bits; "all" covers the other two. */
    /** Module sources and logs (/data/adb, /data/local/tmp). */
    const val ROOT_DIRS = 1
    /** App data and app code (/data/data, /data/app ...). */
    const val ROOT_USER_DATA = 2
    /** Everything except virtual and duplicate mounts. */
    const val ROOT_ALL = 4

    /** Result of an index lookup. [paths] is null when the index was not used. */
    class SearchResult(val paths: List<String>?, val failed: Boolean = false)

    private class IndexedRoot(val path: String, val scannedAt: Long)

    /** In-memory copy of the finished roots so the UI never has to touch the database. */
    @Volatile
    private var rootsCache: List<IndexedRoot>? = null

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

    /** Reloads the in-memory roots. Call from a background thread. */
    fun refreshRootsCache() {
        try {
            val roots = mutableListOf<IndexedRoot>()
            database().rawQuery(
                "SELECT path, scanned_at FROM roots WHERE scanned_at > 0", null
            ).use {
                while (it.moveToNext()) {
                    roots += IndexedRoot(it.getString(0), it.getLong(1))
                }
            }
            rootsCache = roots
        } catch (e: SQLiteException) {
            e.printStackTrace()
            rootsCache = null
        }
    }

    // --- Scope: which extra roots (app data through Shizuku/root, root folders) are indexed ---

    private fun storageRoot(): String =
        normalizeRoot(Environment.getExternalStorageDirectory().path)

    private fun isAppDataRoot(root: String): Boolean =
        root.endsWith("/Android/data") || root.endsWith("/Android/obb")

    private fun needsRoot(root: String): Boolean =
        root == "/" || root == "/data" || root.startsWith("/data/")

    /** Roots that the scope settings manage; everything else was picked by the user. */
    private fun isManagedRoot(root: String): Boolean = isAppDataRoot(root) || needsRoot(root)

    private fun rootScopeRoots(mask: Int): List<String> {
        if (mask and ROOT_ALL != 0) {
            return listOf("/")
        }
        val roots = mutableListOf<String>()
        if (mask and ROOT_DIRS != 0) {
            roots += listOf("/data/adb", "/data/local/tmp")
        }
        if (mask and ROOT_USER_DATA != 0) {
            roots += listOf("/data/local", "/data/data", "/data/app", "/data/user_de")
        }
        return roots
    }

    /**
     * Makes the list of roots match the scope settings. Call from a background thread.
     * @return whether any root was added or removed
     */
    fun applyScope(): Boolean {
        val desired = mutableListOf<String>()
        if (Settings.FILE_INDEX_APP_DATA.valueCompat) {
            val storage = storageRoot()
            desired += "$storage/Android/data"
            desired += "$storage/Android/obb"
        }
        desired += rootScopeRoots(Settings.FILE_INDEX_ROOT_MASK.valueCompat)
        if (desired.isNotEmpty() && Settings.ROOT_STRATEGY.valueCompat == RootStrategy.NEVER) {
            // These folders can only be read through the root/Shizuku file layer.
            Settings.ROOT_STRATEGY.putValue(RootStrategy.AUTOMATIC)
        }
        val current = getRoots()
        val remove = current.filter { isManagedRoot(it) && it !in desired }
        val add = desired.filter { it !in current }
        removeRoots(remove)
        for (root in add) {
            addRoot(root)
        }
        return remove.isNotEmpty() || add.isNotEmpty()
    }

    private fun selectFolders(root: String, indexedRoots: List<String>): List<String> {
        val prefix = root.trimEnd('/')
        val covered = indexedRoots.any { root == it || root.startsWith(it.trimEnd('/') + "/") }
        return when {
            covered -> listOf(prefix)
            Settings.FILE_INDEX_PARTIAL_SEARCH.valueCompat ->
                indexedRoots.filter { it.startsWith("$prefix/") }.map { it.trimEnd('/') }
            else -> emptyList()
        }
    }

    /** Whether a search in [searchRoot] would be answered from the index. Cheap, UI-safe. */
    fun canSearch(searchRoot: String): Boolean {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat) {
            return false
        }
        val roots = rootsCache ?: return false
        return selectFolders(normalizeRoot(searchRoot), roots.map { it.path }).isNotEmpty()
    }

    /** Whether the index for [searchRoot] was last refreshed a while ago. */
    fun isStale(searchRoot: String): Boolean {
        val roots = rootsCache ?: return false
        val root = normalizeRoot(searchRoot)
        val used = selectFolders(root, roots.map { it.path })
        val oldest = roots.filter { r -> used.any { it == r.path.trimEnd('/') || it.startsWith(r.path.trimEnd('/') + "/") } }
            .minOfOrNull { it.scannedAt } ?: return false
        return System.currentTimeMillis() - oldest > STALE_AFTER_MILLIS
    }

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
        refreshRootsCache()
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
        refreshRootsCache()
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
        refreshRootsCache()
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
                "SELECT COUNT(*) FROM roots WHERE ABS(scanned_at) < ?",
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

    private enum class Outcome { DONE, ABORTED, FAILED }

    private fun scanAllRoots(force: Boolean) {
        val db = database()
        val roots = getRoots()
        if (roots.isEmpty()) {
            return
        }
        var total = 0
        var normalTotal = 0
        var rootTotal = 0
        // The previous scan's size is the best guess for how much work there is.
        val estimatedTotal = getEntryCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var aborted = false
        var failed = 0
        FileIndexNotification.showProgress(0, estimatedTotal, force = true)
        // Plain storage first, elevated roots last: they are the slow and the optional ones.
        for (root in roots.sortedBy { if (needsRoot(it)) 2 else if (isAppDataRoot(it)) 1 else 0 }) {
            if (cancelRequested || !canScanNow(force)) {
                aborted = true
                break
            }
            val scanId = System.currentTimeMillis()
            val elevated = needsRoot(root)
            val budget = if (elevated) MAX_ROOT_ENTRIES - rootTotal else MAX_ENTRIES - normalTotal
            val outcome = scanRoot(db, root, scanId, force, budget) {
                total += it
                if (elevated) rootTotal += it else normalTotal += it
                FileIndexNotification.showProgress(total, estimatedTotal)
            }
            when (outcome) {
                Outcome.DONE -> {
                    try {
                        db.execSQL(
                            "DELETE FROM entries WHERE root = ? AND scan <> ?",
                            arrayOf<Any>(root, scanId)
                        )
                        db.execSQL(
                            "UPDATE roots SET scanned_at = ? WHERE path = ?",
                            arrayOf<Any>(scanId, root)
                        )
                    } catch (e: SQLiteException) {
                        e.printStackTrace()
                    }
                }
                Outcome.ABORTED -> {
                    aborted = true
                }
                Outcome.FAILED -> {
                    failed++
                    try {
                        // A negative time marks "tried and failed"; a good old scan is kept.
                        db.execSQL(
                            "UPDATE roots SET scanned_at = ? WHERE path = ? AND scanned_at <= 0",
                            arrayOf<Any>(-scanId, root)
                        )
                    } catch (e: SQLiteException) {
                        e.printStackTrace()
                    }
                }
            }
            if (aborted) {
                break
            }
        }
        refreshRootsCache()
        if (aborted) {
            FileIndexNotification.cancel()
        } else {
            FileIndexNotification.showDone(total, failed)
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

    /**
     * Receives the entries of one root and writes them to the database in batches. Entries are
     * tagged with the scan id; older rows are only deleted once the whole root has been scanned.
     */
    private class Sink(
        private val db: SQLiteDatabase,
        private val root: String,
        private val scanId: Long,
        private val budget: Int,
        private val force: Boolean,
        private val onInserted: (Int) -> Unit
    ) {
        private val statement = db.compileStatement(
            "INSERT OR REPLACE INTO entries(path, fold, root, scan) VALUES(?, ?, ?, ?)"
        )
        private var inserted = 0
        private var inBatch = 0
        private var batches = 0

        /** Stopped because of cancel, low battery or an interrupt. */
        var aborted = false
            private set

        init {
            db.beginTransaction()
        }

        /** @return whether the walk should go on */
        fun add(path: String, name: String): Boolean {
            if (cancelRequested || Thread.currentThread().isInterrupted) {
                aborted = true
                return false
            }
            if (inserted >= budget) {
                // The cap was reached: keep what we have as a complete (if truncated) index.
                return false
            }
            statement.clearBindings()
            statement.bindString(1, path)
            statement.bindString(2, fold(name))
            statement.bindString(3, root)
            statement.bindLong(4, scanId)
            statement.executeInsert()
            inserted++
            inBatch++
            if (inBatch >= BATCH_SIZE) {
                db.setTransactionSuccessful()
                db.endTransaction()
                onInserted(inBatch)
                inBatch = 0
                batches++
                if (batches % BATTERY_CHECK_EVERY_BATCHES == 0 && !canScanNow(force)) {
                    aborted = true
                    return false
                }
                try {
                    // Let other work on the database and the CPU through.
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    aborted = true
                    return false
                }
                db.beginTransaction()
            }
            return true
        }

        fun close() {
            if (db.inTransaction()) {
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            onInserted(inBatch)
            inBatch = 0
        }
    }

    private fun scanRoot(
        db: SQLiteDatabase,
        root: String,
        scanId: Long,
        force: Boolean,
        budget: Int,
        onInserted: (Int) -> Unit
    ): Outcome {
        val sink = Sink(db, root, scanId, budget, force, onInserted)
        val readable = try {
            when {
                needsRoot(root) ->
                    walkWithRootFind(root, sink) || (root != "/" && walkFileLayer(root, sink))
                isAppDataRoot(root) -> walkFileLayer(root, sink)
                else -> walkFile(File(root), sink)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            sink.close()
        }
        return when {
            sink.aborted -> Outcome.ABORTED
            readable -> Outcome.DONE
            else -> Outcome.FAILED
        }
    }

    /** Plain walk with java.io, for everything the app can read itself. */
    private fun walkFile(rootFile: File, sink: Sink): Boolean {
        if (!rootFile.isDirectory) {
            return false
        }
        val stack = ArrayDeque<File>()
        stack.addLast(rootFile)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                if (!sink.add(child.path, child.name)) {
                    return true
                }
                if (child.isDirectory && !isExcluded(child) && !isSymbolicLink(child)) {
                    stack.addLast(child)
                }
            }
        }
        return true
    }

    /**
     * Walk through the app's own file layer, which uses Shizuku or root as the root strategy
     * says. Slow (one IPC call per folder and per entry) but works for Android/data and obb.
     *
     * @return false if the top folder could not be opened at all
     */
    private fun walkFileLayer(rootPath: String, sink: Sink): Boolean {
        val stack = ArrayDeque<Path>()
        stack.addLast(Paths.get(rootPath))
        var first = true
        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            try {
                Files.newDirectoryStream(directory).use { stream ->
                    first = false
                    for (child in stream) {
                        val path = child.toString()
                        if (!sink.add(path, path.substringAfterLast('/'))) {
                            return true
                        }
                        val isDirectory = try {
                            Files.readAttributes(
                                child, BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS
                            ).isDirectory
                        } catch (e: IOException) {
                            false
                        }
                        if (isDirectory) {
                            stack.addLast(child)
                        }
                    }
                }
            } catch (e: Exception) {
                if (first) {
                    return false
                }
                // A folder we may not read (or that vanished): skip it.
            }
        }
        return true
    }

    private fun findCommand(root: String): String =
        if (root == "/") {
            // Virtual file systems, and mounts that show the same files a second time.
            val skipped = listOf(
                "/proc", "/sys", "/dev", "/storage", "/sdcard", "/mnt", "/data/media",
                "/data/user", "/data_mirror", "/apex", "/acct", "/config", "/linkerconfig",
                "/debug_ramdisk", "/metadata", "/cache"
            )
            "find -H / \\( " + skipped.joinToString(" -o ") { "-path $it" } +
                " \\) -prune -o -print 2>/dev/null"
        } else {
            "find -H '$root' 2>/dev/null"
        }

    /**
     * Lists a tree with `find` run through su, about 50 times faster than walking it with one
     * call per folder. `--mount-master` is needed on some root managers: without it su runs in the
     * app's own mount namespace, where other apps' data folders are hidden.
     *
     * @return false if root gave no output at all
     */
    private fun walkWithRootFind(root: String, sink: Sink): Boolean {
        val command = findCommand(root)
        val attempts = listOf(
            arrayOf("su", "--mount-master", "-c", command),
            arrayOf("su", "-c", command)
        )
        for (arguments in attempts) {
            val process = try {
                Runtime.getRuntime().exec(arguments)
            } catch (e: IOException) {
                return false
            }
            var lines = 0
            try {
                process.outputStream.close()
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty() || line == root) {
                            continue
                        }
                        lines++
                        if (!sink.add(line, line.substringAfterLast('/'))) {
                            break
                        }
                    }
                }
            } finally {
                // Closing the pipe makes find stop at its next write.
                process.destroy()
            }
            if (lines > 0 || sink.aborted) {
                return true
            }
        }
        return false
    }

    private fun escapeLike(string: String): String =
        string.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Looks up names containing [query] below [searchRoot].
     *
     * The result has no paths if the index cannot answer this search (disabled, folder not
     * covered), and `failed` set if a database problem forced that. Callers then walk the tree.
     */
    fun search(searchRoot: String, query: String): SearchResult {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat || query.isBlank()) {
            return SearchResult(null)
        }
        val root = normalizeRoot(searchRoot)
        try {
            val db = database()
            val indexedRoots = mutableListOf<String>()
            db.rawQuery("SELECT path FROM roots WHERE scanned_at > 0", null).use {
                while (it.moveToNext()) {
                    indexedRoots += it.getString(0)
                }
            }
            // Folders to read from the index: just the search folder if it is fully covered,
            // otherwise (only if the user allowed it) the indexed folders below it.
            val searchFolders = selectFolders(root, indexedRoots)
            if (searchFolders.isEmpty()) {
                return SearchResult(null)
            }
            val folded = fold(query)
            val likeArg = "%" + escapeLike(folded) + "%"
            val results = LinkedHashSet<String>()
            for (folder in searchFolders) {
                if (results.size >= MAX_RESULTS) {
                    break
                }
                // '0' is the character right after '/', so this is exactly "paths below folder".
                val low = "$folder/"
                val high = "${folder}0"
                db.rawQuery(
                    "SELECT path FROM entries WHERE path > ? AND path < ? " +
                        "AND fold LIKE ? ESCAPE '\\' ORDER BY fold LIMIT $MAX_RESULTS",
                    arrayOf(low, high, likeArg)
                ).use {
                    while (it.moveToNext() && results.size < MAX_RESULTS) {
                        results += it.getString(0)
                    }
                }
            }
            return SearchResult(results.toList())
        } catch (e: Exception) {
            e.printStackTrace()
            return SearchResult(null, failed = true)
        }
    }
}
