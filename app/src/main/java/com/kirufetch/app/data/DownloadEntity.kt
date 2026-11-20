package com.kirufetch.app.data

data class DownloadEntity(
    val id: String,
    val url: String,
    val pageUrl: String?,
    val fileName: String,
    val outputPath: String,
    val kind: String,
    val status: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val audioPath: String? = null,
    val variantUrl: String? = null,
    val variantLabel: String? = null
) {
    val progress: Int
        get() = if (totalBytes > 0) {
            (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        } else 0

    val isRunning: Boolean get() = status == STATUS_RUNNING
    val isPaused: Boolean get() = status == STATUS_PAUSED
    val isDone: Boolean get() = status == STATUS_DONE
    val isFailed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELED = "CANCELED"
    }
}
