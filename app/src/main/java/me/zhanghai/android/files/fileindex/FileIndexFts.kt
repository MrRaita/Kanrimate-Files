/*
 * Copyright (c) 2026 MrRaita
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileindex

import android.database.sqlite.SQLiteDatabase

/**
 * Support code for the optional FTS4 engine.
 *
 * FTS4 can only match whole words or word prefixes, so to find a piece in the middle of a name we
 * index every distinct 3-character piece of the name ("trigram") as its own word. Each character is
 * written as 4 base-36 digits so that every punctuation character survives the FTS "simple"
 * tokenizer. A search then needs all the pieces of the query to be present; the real match is
 * confirmed afterwards against the folded name.
 */
object FileIndexFts {
    const val TABLE = "entries_fts"

    const val CREATE_SQL =
        "CREATE VIRTUAL TABLE $TABLE USING fts4(path, tri, notindexed=path, tokenize=simple)"

    private const val DIGITS = 4

    /** Whether this device's SQLite can create the table above. */
    val isSupported: Boolean by lazy {
        try {
            val db = SQLiteDatabase.create(null)
            try {
                db.execSQL(CREATE_SQL)
                true
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun codePoints(string: String): IntArray {
        val result = IntArray(string.codePointCount(0, string.length))
        var index = 0
        var i = 0
        while (i < string.length) {
            val codePoint = string.codePointAt(i)
            result[index++] = codePoint
            i += Character.charCount(codePoint)
        }
        return result
    }

    private fun encode(points: IntArray, start: Int, length: Int, out: StringBuilder) {
        for (i in start until start + length) {
            val digits = Integer.toString(points[i], 36)
            for (pad in digits.length until DIGITS) {
                out.append('0')
            }
            out.append(digits)
        }
    }

    private fun pieces(fold: String): Set<String> {
        val points = codePoints(fold)
        val result = LinkedHashSet<String>()
        if (points.size < 3) {
            if (points.isNotEmpty()) {
                val builder = StringBuilder()
                encode(points, 0, points.size, builder)
                result += builder.toString()
            }
            return result
        }
        for (i in 0..points.size - 3) {
            val builder = StringBuilder(3 * DIGITS)
            encode(points, i, 3, builder)
            result += builder.toString()
        }
        return result
    }

    /** The words to store for a folded file name. */
    fun indexTokens(fold: String): String = pieces(fold).joinToString(" ")

    /** The MATCH expression for a folded query, or null if it is too short for this engine. */
    fun matchQuery(foldedQuery: String): String? {
        if (foldedQuery.codePointCount(0, foldedQuery.length) < 3) {
            return null
        }
        return pieces(foldedQuery).joinToString(" ")
    }
}
