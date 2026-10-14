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
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.powerManager
import me.zhanghai.android.files.util.AppExecutors
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.provider.root.SuBinary
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
    /** The scan stops instead of filling the phone's storage. */
    private const val MIN_FREE_BYTES = 150L * 1024 * 1024
    private const val REFRESH_AFTER_MILLIS = 12L * 60 * 60 * 1000
    private const val MAX_RESULTS = 5000

    private const val STALE_AFTER_MILLIS = 30L * 60 * 1000
    private const val RECENT_SCAN_MILLIS = 15L * 60 * 1000

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

    /** Changes whenever the list of roots changes, so a running scan can tell it is outdated. */
    @Volatile
    private var rootsVersion = 0

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

    /**
     * The only roots that are ever listed through su. Anything else, even below /data, is a
     * folder the user picked and is read like any other folder, never as root.
     */
    private val ROOT_ALLOWED = setOf(
        "/", "/data/adb", "/data/local/tmp", "/data/local", "/data/data", "/data/app",
        "/data/user_de"
    )

    private fun needsRoot(root: String): Boolean = root in ROOT_ALLOWED

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
        // A folder inside another chosen folder is already covered by it; scanning both would
        // write the same entries twice.
        return roots.filter { root -> roots.none { it != root && root.startsWith("$it/") } }
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
        if (remove.isNotEmpty() || add.isNotEmpty()) {
            IndexLog.log("applyScope: removing $remove, adding $add")
        }
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
        rootsVersion++
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
        rootsVersion++
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
        rootsVersion++
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

    /**
     * Called when the app is opened: makes sure the automatic jobs exist and, if the user wants
     * it, refreshes the index. A scan that finished a few minutes ago is not repeated.
     */
    fun onAppOpened() {
        if (!Settings.FILE_INDEX_ENABLED.valueCompat) {
            return
        }
        AppExecutors.io.execute {
            FileIndexScheduler.update()
            if (Settings.FILE_INDEX_AUTO_OPEN.valueCompat && !wasScannedRecently()) {
                IndexLog.log("auto: refreshing because the app was opened")
                startScan(force = false)
            }
        }
    }

    private fun wasScannedRecently(): Boolean =
        try {
            val newest = database().rawQuery(
                "SELECT MIN(ABS(scanned_at)) FROM roots", null
            ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }
            newest > 0 && System.currentTimeMillis() - newest < RECENT_SCAN_MILLIS
        } catch (e: SQLiteException) {
            false
        }

    /** Changes every time a scan starts or ends, so screens can refresh what they show. */
    val scanEvents = MutableLiveData<Long>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(textRes: Int, vararg args: Any) {
        mainHandler.post {
            try {
                Toast.makeText(application, application.getString(textRes, *args), Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Exception) {
                // A toast is a convenience; never let it break anything.
            }
        }
    }

    fun cancelScan() {
        cancelRequested = true
    }

    @Volatile
    private var rescanPending = false

    @Volatile
    private var rescanForce = false

    @Volatile
    private var scanQuiet = false

    /**
     * @param force ignore the battery and power saving checks (user asked for it)
     * @param quiet no start and finish messages on screen (scheduled background runs)
     */
    fun startScan(force: Boolean, quiet: Boolean = false) {
        IndexLog.log("startScan requested (force=$force, quiet=$quiet, scanning=${scanning.get()})")
        if (!scanning.compareAndSet(false, true)) {
            // A scan is still winding down (for example it was just cancelled): run again after.
            rescanForce = rescanForce || force
            rescanPending = true
            scanQuiet = scanQuiet && quiet
            return
        }
        scanQuiet = quiet
        if (!quiet) {
            toast(R.string.settings_file_index_rebuild_started)
        }
        scanEvents.postValue(System.currentTimeMillis())
        cancelRequested = false
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                var runForce = force
                while (true) {
                    val versionAtStart = rootsVersion
                    val completed = scanAllRoots(runForce)
                    if (!rescanPending) {
                        break
                    }
                    rescanPending = false
                    if (completed && rootsVersion == versionAtStart && (runForce || !rescanForce)) {
                        // The finished scan already did what the extra request asked for.
                        IndexLog.log("scan: skipping a repeated request (nothing changed meanwhile)")
                        rescanForce = false
                        break
                    }
                    runForce = rescanForce
                    rescanForce = false
                    cancelRequested = false
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                IndexLog.log("scan thread crashed", e)
            } finally {
                scanning.set(false)
                scanEvents.postValue(System.currentTimeMillis())
            }
        }, "FileIndex.Scan").start()
    }

    private fun hasFreeSpace(): Boolean =
        try {
            application.filesDir.usableSpace >= MIN_FREE_BYTES
        } catch (e: Exception) {
            true
        }

    private fun canScanNow(force: Boolean): Boolean {
        if (!hasFreeSpace()) {
            return false
        }
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

    /** @return whether the scan ran to the end (was not cancelled or stopped) */
    private fun scanAllRoots(force: Boolean): Boolean {
        val db = database()
        val roots = getRoots()
        val scanStart = System.currentTimeMillis()
        logScanHeader(force, roots)
        if (roots.isEmpty()) {
            IndexLog.log("scan: no roots, nothing to do")
            return true
        }
        var total = 0
        var normalTotal = 0
        var rootTotal = 0
        // The previous scan's size is the best guess for how much work there is.
        val estimatedTotal = getEntryCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var aborted = false
        var failed = 0
        refreshRootsCache()
        val scannedBefore = rootsCache.orEmpty().map { it.path }.toSet()
        FileIndexNotification.showProgress(0, estimatedTotal, force = true)
        // Plain storage first, elevated roots last: they are the slow and the optional ones.
        for (root in roots.sortedBy { if (needsRoot(it)) 2 else if (isAppDataRoot(it)) 1 else 0 }) {
            if (cancelRequested || !canScanNow(force)) {
                aborted = true
                IndexLog.log(
                    "scan: stopped before $root (cancel=$cancelRequested, " +
                        "canScanNow=${canScanNow(force)})"
                )
                break
            }
            val elevated = needsRoot(root)
            // A background refresh never asks for Shizuku/root permission for a root that has
            // not been scanned successfully before; that only happens when the user starts it.
            if (!force && (elevated || isAppDataRoot(root)) && root !in scannedBefore) {
                IndexLog.log("root $root: skipped (background refresh, never scanned before)")
                continue
            }
            val scanId = System.currentTimeMillis()
            val budget = if (elevated) MAX_ROOT_ENTRIES - rootTotal else MAX_ENTRIES - normalTotal
            val outcome = scanRoot(db, root, scanId, force, budget) {
                total += it
                if (elevated) rootTotal += it else normalTotal += it
                FileIndexNotification.showProgress(total, estimatedTotal)
            }
            IndexLog.log("root $root: outcome=$outcome")
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
        IndexLog.log(
            "scan: finished in ${System.currentTimeMillis() - scanStart} ms, new entries=$total " +
                "(normal $normalTotal, elevated $rootTotal), aborted=$aborted, failed roots=$failed, " +
                "entries in database now=${getEntryCount()}, " +
                "database file=${database().path?.let { File(it).length() / 1024 } ?: -1} KB"
        )
        if (aborted) {
            FileIndexNotification.cancel()
        } else {
            FileIndexNotification.showDone(total, failed)
            if (!scanQuiet) {
                toast(R.string.file_index_toast_done_format, getEntryCount())
            }
        }
        return !aborted
    }

    private fun logScanHeader(force: Boolean, roots: List<String>) {
        val battery = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val freeMb = try {
            application.filesDir.usableSpace / 1024 / 1024
        } catch (e: Exception) {
            -1L
        }
        IndexLog.log("==== scan start: force=$force, roots=$roots")
        IndexLog.log(
            "scan: enabled=${Settings.FILE_INDEX_ENABLED.valueCompat}, " +
                "appData=${Settings.FILE_INDEX_APP_DATA.valueCompat}, " +
                "rootMask=${Settings.FILE_INDEX_ROOT_MASK.valueCompat}, " +
                "rootStrategy=${Settings.ROOT_STRATEGY.valueCompat}, " +
                "battery=$level% plugged=$plugged, powerSave=${powerManager.isPowerSaveMode}, " +
                "free=$freeMb MB, entries before=${getEntryCount()}"
        )
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
        private val startedAt = System.currentTimeMillis()
        private val topCounts = HashMap<String, Int>()
        private val firstPaths = ArrayList<String>()
        private val skippedSamples = ArrayList<String>()

        val count: Int
            get() = inserted

        /** The entry cap was reached, so the index of this root is cut short. */
        var capped = false
            private set

        /** Folders that could not be listed. */
        var skipped = 0
            private set

        var abortReason: String? = null
            private set

        /** Notes a folder that could not be read (only the first few are kept for the log). */
        fun skip(path: String) {
            skipped++
            if (skippedSamples.size < 8) {
                skippedSamples += path
            }
        }

        private fun topKey(path: String): String {
            val relative = if (root == "/") path.removePrefix("/") else path.removePrefix(root).trimStart('/')
            val first = relative.substringBefore('/')
            if (!relative.contains('/')) {
                return "(top level)"
            }
            if (root == "/" && first == "data") {
                return "/data/" + relative.substringAfter('/').substringBefore('/')
            }
            return if (root == "/") "/$first" else first
        }

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
                abortReason = if (cancelRequested) "cancel requested" else "thread interrupted"
                return false
            }
            if (inserted >= budget) {
                // The cap was reached: keep what we have as a complete (if truncated) index.
                capped = true
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
            if (firstPaths.size < 5) {
                firstPaths += path
            }
            val key = topKey(path)
            topCounts[key] = (topCounts[key] ?: 0) + 1
            if (inBatch >= BATCH_SIZE) {
                db.setTransactionSuccessful()
                db.endTransaction()
                onInserted(inBatch)
                inBatch = 0
                batches++
                if (batches % BATTERY_CHECK_EVERY_BATCHES == 0 && !canScanNow(force)) {
                    aborted = true
                    abortReason = "battery, power saving or free space check failed"
                    return false
                }
                try {
                    // Let other work on the database and the CPU through.
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    aborted = true
                    abortReason = "interrupted while sleeping"
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
            val top = topCounts.entries.sortedByDescending { it.value }.take(25)
                .joinToString { it.key + "=" + it.value }
            IndexLog.log(
                "  sink $root: $inserted entries in ${System.currentTimeMillis() - startedAt} ms, " +
                    "capped=$capped (budget $budget), aborted=$aborted" +
                    (abortReason?.let { " ($it)" } ?: "") + ", unreadable folders=$skipped" +
                    (if (skippedSamples.isEmpty()) "" else " e.g. " + skippedSamples.joinToString(" | "))
            )
            IndexLog.log("  first entries: " + firstPaths.joinToString(" | "))
            IndexLog.log("  by top folder: $top")
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
        val method = when {
            needsRoot(root) -> "root find (fallback: file layer)"
            isAppDataRoot(root) -> "file layer (Shizuku/root strategy)"
            else -> "plain java.io walk"
        }
        IndexLog.log("root $root: start, method=$method, budget=$budget, elevated=${needsRoot(root)}")
        val readable = try {
            when {
                needsRoot(root) ->
                    walkWithRootFind(root, sink) ||
                        (root != "/" && run {
                            IndexLog.log("  root find gave nothing, trying the file layer")
                            walkFileLayer(root, sink)
                        })
                isAppDataRoot(root) -> walkFileLayer(root, sink)
                else -> walkFile(File(root), sink)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            IndexLog.log("  walk threw", e)
            false
        } finally {
            sink.close()
        }
        IndexLog.log("root $root: readable=$readable")
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
            val directory = stack.removeLast()
            val children = directory.listFiles()
            if (children == null) {
                sink.skip(directory.path)
                continue
            }
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
                    IndexLog.log("  file layer cannot open top folder $rootPath", e)
                    return false
                }
                // A folder we may not read (or that vanished): skip it.
                sink.skip(directory.toString())
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
            "find -H '" + root.replace("'", "'\\''") + "' 2>/dev/null"
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
        val su = SuBinary.find()
        if (su == null) {
            IndexLog.log("  no su binary found")
            return false
        }
        IndexLog.log("  su=$su, command: $command")
        val attempts = listOf(
            arrayOf(su, "--mount-master", "-c", command),
            arrayOf(su, "-c", command)
        )
        for (arguments in attempts) {
            val label = if (arguments.contains("--mount-master")) "mount-master" else "plain su"
            val attemptStart = System.currentTimeMillis()
            val process = try {
                Runtime.getRuntime().exec(arguments)
            } catch (e: IOException) {
                IndexLog.log("  [$label] cannot start su", e)
                return false
            }
            val errors = StringBuffer()
            val errorReader = Thread({
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (errors.length < 600) {
                                errors.append(line).append(" / ")
                            }
                        }
                    }
                } catch (e: IOException) {
                    // The process was destroyed.
                }
            }, "FileIndex.SuErr")
            errorReader.isDaemon = true
            errorReader.start()
            var endedNaturally = false
            var lines = 0
            // Reading blocks while su waits for the user's permission answer, so something else
            // has to be able to stop the process when the scan is cancelled.
            val finished = AtomicBoolean(false)
            val watcher = Thread({
                while (!finished.get()) {
                    if (cancelRequested) {
                        process.destroy()
                        break
                    }
                    try {
                        Thread.sleep(300)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }, "FileIndex.SuWatch")
            watcher.isDaemon = true
            watcher.start()
            try {
                process.outputStream.close()
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) {
                            endedNaturally = true
                            break
                        }
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
                finished.set(true)
                watcher.interrupt()
                // Closing the pipe makes find stop at its next write.
                process.destroy()
            }
            val exit = if (endedNaturally) {
                try {
                    process.waitFor().toString()
                } catch (e: InterruptedException) {
                    "?"
                }
            } else {
                "not finished (stopped early)"
            }
            IndexLog.log(
                "  [$label] $lines lines in ${System.currentTimeMillis() - attemptStart} ms, " +
                    "exit=$exit, stderr=" + errors.toString().ifEmpty { "(none)" }
            )
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
        val searchStart = System.currentTimeMillis()
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
                IndexLog.log("search in $root (query length ${query.length}): index cannot answer, walking the tree")
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
            IndexLog.log(
                "search in $root (query length ${query.length}): ${results.size} results from index " +
                    "folders $searchFolders in ${System.currentTimeMillis() - searchStart} ms"
            )
            return SearchResult(results.toList())
        } catch (e: Exception) {
            e.printStackTrace()
            IndexLog.log("search in $root failed", e)
            return SearchResult(null, failed = true)
        }
    }
}
