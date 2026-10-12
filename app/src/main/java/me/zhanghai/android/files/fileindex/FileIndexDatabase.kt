/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import me.zhanghai.android.files.app.application

/**
 * The persistent file name index. Only names and paths are stored, never file contents.
 *
 * `entries` is clustered by path so that "everything below a directory" is a cheap range scan.
 */
class FileIndexDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE roots (path TEXT PRIMARY KEY NOT NULL, " +
                "scanned_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE entries (path TEXT PRIMARY KEY NOT NULL, fold TEXT NOT NULL, " +
                "root TEXT NOT NULL, scan INTEGER NOT NULL) WITHOUT ROWID"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The index is only a cache; rebuild it from scratch.
        db.execSQL("DROP TABLE IF EXISTS entries")
        db.execSQL("DROP TABLE IF EXISTS roots")
        onCreate(db)
    }

    companion object {
        private const val NAME = "file_index.db"
        private const val VERSION = 1

        val instance: FileIndexDatabase by lazy { FileIndexDatabase(application) }

        fun delete(context: Context) {
            instance.close()
            context.deleteDatabase(NAME)
        }
    }
}
