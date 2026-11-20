package com.kirufetch.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Bookmark(
    val id: Long,
    val title: String,
    val url: String,
    val createdAt: Long
)

data class HistoryEntry(
    val id: Long,
    val title: String,
    val url: String,
    val visitedAt: Long
)

class BrowserStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                visitedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_time ON history(visitedAt DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    fun addBookmark(title: String, url: String) {
        if (url.isBlank()) return
        writableDatabase.insertWithOnConflict(
            "bookmarks", null,
            ContentValues().apply {
                put("title", title.ifBlank { url })
                put("url", url)
                put("createdAt", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        refreshBookmarks()
    }

    fun removeBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
        refreshBookmarks()
    }

    fun isBookmarked(url: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)
        ).use { return it.moveToFirst() }
    }

    fun addHistory(title: String, url: String) {
        if (url.isBlank()) return
        writableDatabase.insert(
            "history", null,
            ContentValues().apply {
                put("title", title.ifBlank { url })
                put("url", url)
                put("visitedAt", System.currentTimeMillis())
            }
        )
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT 2000)"
        )
        refreshHistory()
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
        refreshHistory()
    }

    fun removeHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        refreshHistory()
    }

    fun refreshBookmarks() {
        val list = mutableListOf<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT * FROM bookmarks ORDER BY createdAt DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                list += Bookmark(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    title = c.str("title").orEmpty(),
                    url = c.str("url").orEmpty(),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt"))
                )
            }
        }
        _bookmarks.value = list
    }

    fun refreshHistory() {
        val list = mutableListOf<HistoryEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM history ORDER BY visitedAt DESC LIMIT 500", null
        ).use { c ->
            while (c.moveToNext()) {
                list += HistoryEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    title = c.str("title").orEmpty(),
                    url = c.str("url").orEmpty(),
                    visitedAt = c.getLong(c.getColumnIndexOrThrow("visitedAt"))
                )
            }
        }
        _history.value = list
    }

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    companion object {
        private const val DB_NAME = "kirufetch_browser.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: BrowserStore? = null

        fun get(context: Context): BrowserStore =
            INSTANCE ?: synchronized(this) {
                BrowserStore(context).also { INSTANCE = it }
            }
    }
}
