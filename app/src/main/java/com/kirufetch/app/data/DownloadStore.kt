package com.kirufetch.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DownloadStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    private val _state = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val state: StateFlow<List<DownloadEntity>> = _state

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                pageUrl TEXT,
                fileName TEXT NOT NULL,
                outputPath TEXT NOT NULL,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                totalBytes INTEGER NOT NULL DEFAULT -1,
                downloadedBytes INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                error TEXT,
                audioPath TEXT,
                variantUrl TEXT,
                variantLabel TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun upsert(entity: DownloadEntity) {
        writableDatabase.insertWithOnConflict(
            TABLE, null, toValues(entity), SQLiteDatabase.CONFLICT_REPLACE
        )
        refresh()
    }

    private val lastFlush = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 进度更新很密集（每 64KB 一次），这里做节流：
     * 同一个任务最多每 PROGRESS_INTERVAL_MS 落库一次，避免主线程被全表查询拖死。
     */
    fun updateProgress(id: String, downloaded: Long, total: Long) {
        val now = System.currentTimeMillis()
        val last = lastFlush[id] ?: 0L
        if (now - last < PROGRESS_INTERVAL_MS) return
        lastFlush[id] = now

        val values = ContentValues().apply {
            put("downloadedBytes", downloaded)
            if (total > 0) put("totalBytes", total)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        refresh()
    }

    /**
     * HLS 的进度单位是分片数，不是字节。单独走百分比落库，
     * 免得把"第 37 片"写成"37 字节"，完成时再回填真实文件大小。
     */
    fun updatePercent(id: String, percent: Int) {
        val now = System.currentTimeMillis()
        val last = lastFlush[id] ?: 0L
        if (now - last < PROGRESS_INTERVAL_MS) return
        lastFlush[id] = now

        val values = ContentValues().apply {
            put("downloadedBytes", percent.toLong())
            put("totalBytes", 100L)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        refresh()
    }

    fun hasActiveTasks(): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE status IN (?, ?) LIMIT 1",
            arrayOf(DownloadEntity.STATUS_QUEUED, DownloadEntity.STATUS_RUNNING)
        ).use { return it.moveToFirst() }
    }

    fun updateStatus(id: String, status: String, error: String? = null, audioPath: String? = null) {
        val values = ContentValues().apply {
            put("status", status)
            put("error", error)
            if (audioPath != null) put("audioPath", audioPath)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        refresh()
    }

    fun updateStatusOnly(id: String, status: String) {
        writableDatabase.update(TABLE, ContentValues().apply { put("status", status) }, "id = ?", arrayOf(id))
        refresh()
    }

    /** 下载完成后成品可能被搬到用户选的目录，路径要跟着更新。 */
    fun updateOutputPath(id: String, path: String) {
        writableDatabase.update(
            TABLE, ContentValues().apply { put("outputPath", path) }, "id = ?", arrayOf(id)
        )
        refresh()
    }

    fun find(id: String): DownloadEntity? {
        readableDatabase.rawQuery("SELECT * FROM $TABLE WHERE id = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    fun pending(): List<DownloadEntity> {
        val list = mutableListOf<DownloadEntity>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE status IN (?, ?) ORDER BY createdAt ASC",
            arrayOf(DownloadEntity.STATUS_QUEUED, DownloadEntity.STATUS_RUNNING)
        ).use { c ->
            while (c.moveToNext()) list += read(c)
        }
        return list
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
        refresh()
    }

    /** 落库后立即刷新一次，用于任务结束等关键节点。 */
    fun flushProgress(id: String, downloaded: Long, total: Long) {
        lastFlush.remove(id)
        val values = ContentValues().apply {
            put("downloadedBytes", downloaded)
            if (total > 0) put("totalBytes", total)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        refresh()
    }

    fun refresh() {
        val list = mutableListOf<DownloadEntity>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY createdAt DESC", null).use { c ->
            while (c.moveToNext()) list += read(c)
        }
        _state.value = list
    }

    private fun toValues(e: DownloadEntity) = ContentValues().apply {
        put("id", e.id)
        put("url", e.url)
        put("pageUrl", e.pageUrl)
        put("fileName", e.fileName)
        put("outputPath", e.outputPath)
        put("kind", e.kind)
        put("status", e.status)
        put("totalBytes", e.totalBytes)
        put("downloadedBytes", e.downloadedBytes)
        put("createdAt", e.createdAt)
        put("error", e.error)
        put("audioPath", e.audioPath)
        put("variantUrl", e.variantUrl)
        put("variantLabel", e.variantLabel)
    }

    private fun read(c: Cursor) = DownloadEntity(
        id = c.str("id").orEmpty(),
        url = c.str("url").orEmpty(),
        pageUrl = c.str("pageUrl"),
        fileName = c.str("fileName").orEmpty(),
        outputPath = c.str("outputPath").orEmpty(),
        kind = c.str("kind").orEmpty(),
        status = c.str("status").orEmpty(),
        totalBytes = c.getLong(c.getColumnIndexOrThrow("totalBytes")),
        downloadedBytes = c.getLong(c.getColumnIndexOrThrow("downloadedBytes")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
        error = c.str("error"),
        audioPath = c.str("audioPath"),
        variantUrl = c.str("variantUrl"),
        variantLabel = c.str("variantLabel")
    )

    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        if (i < 0 || isNull(i)) return null
        return getString(i)
    }

    companion object {
        private const val DB_NAME = "kirufetch.db"
        private const val DB_VERSION = 3
        private const val TABLE = "downloads"
        private const val PROGRESS_INTERVAL_MS = 400L

        @Volatile
        private var INSTANCE: DownloadStore? = null

        fun get(context: Context): DownloadStore =
            INSTANCE ?: synchronized(this) {
                DownloadStore(context).also { INSTANCE = it }
            }
    }
}
