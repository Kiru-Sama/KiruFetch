package com.kirufetch.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.kirufetch.app.data.DownloadEntity
import com.kirufetch.app.data.DownloadStore
import com.kirufetch.app.settings.AppPrefs
import com.kirufetch.app.sniff.MediaCandidate
import com.kirufetch.app.ui.screens.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载队列的唯一入口。
 *
 * 真正的串行：单消费者 [drain] 一次只取一个任务，前一个 join 完才取下一个。
 * 暂停/取消都是取消子任务，队列不会因为某个任务挂住而堵死。
 */
class DownloadManager(context: Context) {

    private val appContext = context.applicationContext
    private val store = DownloadStore.get(appContext)
    private val prefs = AppPrefs(appContext)
    private val directDownloader = DirectDownloader()
    private val hlsDownloader = HlsDownloader()
    private val dashDownloader = DashDownloader()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private val queued = HashMap<String, QueuedTask>()
    private val running = HashMap<String, Job>()
    private var worker: Job? = null

    private data class QueuedTask(val candidate: MediaCandidate, val variantUrl: String?)

    fun enqueue(
        candidate: MediaCandidate,
        variantUrl: String? = null,
        variantLabel: String? = null,
        onQueued: ((String) -> Unit)? = null
    ) {
        // 同一个候选重复点下载时不要重置已在跑的任务状态
        synchronized(lock) {
            if (running.containsKey(candidate.id) || queued.containsKey(candidate.id)) return
        }

        val output = File(outputDir(), safeFileName(candidate))
        val kindName = when (candidate.kind) {
            MediaCandidate.Kind.HLS -> "HLS"
            MediaCandidate.Kind.DASH -> "DASH"
            else -> "DIRECT"
        }

        store.upsert(
            DownloadEntity(
                id = candidate.id,
                url = candidate.url,
                pageUrl = candidate.pageUrl,
                fileName = output.name,
                outputPath = output.absolutePath,
                kind = kindName,
                status = DownloadEntity.STATUS_QUEUED,
                variantUrl = variantUrl,
                variantLabel = variantLabel
            )
        )
        onQueued?.invoke(candidate.id)
        submit(candidate, variantUrl, startService = true)
    }

    fun start(candidate: MediaCandidate, variantUrl: String? = null) {
        submit(candidate, variantUrl, startService = true)
    }

    private fun submit(candidate: MediaCandidate, variantUrl: String?, startService: Boolean) {
        synchronized(lock) {
            if (running.containsKey(candidate.id) || queued.containsKey(candidate.id)) return
            queued[candidate.id] = QueuedTask(candidate, variantUrl)
            queue.addLast(candidate.id)
            if (worker?.isActive != true) {
                worker = scope.launch { drain() }
            }
        }
        if (startService) DownloadService.start(appContext, candidate.id)
    }

    private suspend fun drain() {
        while (true) {
            val next: Pair<String, QueuedTask>? = synchronized(lock) {
                val id = queue.removeFirstOrNull()
                if (id == null) {
                    worker = null
                    null
                } else {
                    queued.remove(id)?.let { id to it }
                }
            }

            if (next == null) {
                stopServiceIfIdle()
                return
            }

            val (id, task) = next
            val job = scope.launch { runTask(task.candidate, task.variantUrl) }
            synchronized(lock) { running[id] = job }
            job.join()
            synchronized(lock) { running.remove(id) }
        }
    }

    private fun stopServiceIfIdle() {
        val active = synchronized(lock) { queue.isNotEmpty() || running.isNotEmpty() }
        if (!active && !store.hasActiveTasks()) {
            DownloadService.stop(appContext)
        }
    }

    fun pause(id: String) {
        val control = DownloadControls.of(id)
        control.paused = true

        synchronized(lock) {
            queue.remove(id)
            queued.remove(id)
        }
        store.updateStatusOnly(id, DownloadEntity.STATUS_PAUSED)
        synchronized(lock) { running[id] }?.cancel()
        cancelNotification(id)
    }

    fun resume(id: String) {
        val entity = store.find(id) ?: return
        val control = DownloadControls.of(id)
        control.paused = false
        control.canceled = false

        store.updateStatusOnly(id, DownloadEntity.STATUS_QUEUED)
        submit(
            MediaCandidate(
                id = entity.id,
                url = entity.url,
                pageUrl = entity.pageUrl,
                kind = MediaCandidate.fromStored(entity.kind)
            ),
            entity.variantUrl,
            startService = true
        )
    }

    fun cancel(id: String) {
        val control = DownloadControls.of(id)
        control.paused = false
        control.canceled = true

        synchronized(lock) {
            queue.remove(id)
            queued.remove(id)
        }
        synchronized(lock) { running[id] }?.cancel()
        DownloadControls.remove(id)

        // 取消/删除后把临时分片清掉，否则 .parts 目录会一直占着几百 MB
        store.find(id)?.let { cleanupTempFiles(it.outputPath) }

        store.delete(id)
        cancelNotification(id)
    }

    private fun cleanupTempFiles(outputPath: String) {
        if (StorageTarget.isDocumentPath(outputPath)) return
        runCatching {
            val target = File(outputPath)
            File(target.absolutePath + ".parts").deleteRecursively()
            File(target.absolutePath + ".part").delete()
            target.parentFile?.listFiles()?.forEach { sibling ->
                if (sibling.name.startsWith(target.name + ".part")) sibling.delete()
            }
        }
    }

    fun delete(id: String) {
        cancel(id)
    }

    private suspend fun runTask(candidate: MediaCandidate, variantUrl: String?) {
        val id = candidate.id
        val control = DownloadControls.of(id)
        val output = File(outputDir(), safeFileName(candidate))
        val headers = HeaderFactory.build(
            targetUrl = candidate.url,
            pageUrl = candidate.pageUrl,
            originalHeaders = candidate.headers,
            userAgent = prefs.userAgent,
            allowCookies = prefs.allowCookies,
            cookies = if (prefs.allowCookies) cookieFor(candidate.url) else null
        )

        store.updateStatusOnly(id, DownloadEntity.STATUS_RUNNING)
        notifyProgress(id, output.name, 0)

        var audioPath: String? = null

        try {
            val file: File = when (candidate.kind) {
                MediaCandidate.Kind.HLS -> {
                    val result = hlsDownloader.download(
                        candidate.url, headers, output,
                        prefs.threads, control, variantUrl
                    ) { done, total ->
                        // 这里的总量是分片数，按百分比落库，别当字节写
                        val percent = if (total > 0) done * 100 / total else 0
                        store.updatePercent(id, percent)
                        notifyProgress(id, output.name, percent)
                    }.getOrThrow()
                    audioPath = result.audioPath
                    result.file
                }

                MediaCandidate.Kind.DASH -> {
                    val result = dashDownloader.download(
                        candidate.url, headers, output, control
                    ) { done, total ->
                        val percent = if (total > 0) done * 100 / total else 0
                        store.updatePercent(id, percent)
                        notifyProgress(id, output.name, percent)
                    }.getOrThrow()
                    audioPath = result.audioPath
                    result.file
                }

                else -> directDownloader.download(
                    candidate.url, headers, output,
                    prefs.threads, control
                ) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    store.updateProgress(id, done, total)
                    notifyProgress(id, output.name, percent)
                }.getOrThrow()
            }

            val size = file.length()
            // 用户在设置里选了目录：拷过去，成功就把临时文件删掉，库里改记 content://
            var finalPath = file.absolutePath
            val treeUri = prefs.downloadTreeUri
            if (!treeUri.isNullOrBlank()) {
                val exported = StorageTarget.export(
                    appContext, file, output.name, android.net.Uri.parse(treeUri)
                )
                if (exported != null) {
                    file.delete()
                    finalPath = exported
                }
            } else if (prefs.saveToGallery) {
                // 没指定目录时写进系统媒体库，相册里直接能看到
                val published = GalleryTarget.publish(appContext, file, output.name)
                if (published != null) {
                    file.delete()
                    finalPath = published
                }
            }
            store.updateOutputPath(id, finalPath)
            // 完成时把真实字节数写回去，列表里的"已完成 · 1 KB"就是这么来的
            store.flushProgress(id, size, size)
            store.updateStatus(id, DownloadEntity.STATUS_DONE, audioPath = audioPath)
            notifyDone(id, output.name, size)
        } catch (pe: PausedException) {
            store.updateStatusOnly(id, DownloadEntity.STATUS_PAUSED)
            cancelNotification(id)
        } catch (ce: CancellationException) {
            if (control.paused) {
                store.updateStatusOnly(id, DownloadEntity.STATUS_PAUSED)
            } else {
                store.updateStatusOnly(id, DownloadEntity.STATUS_CANCELED)
            }
            cancelNotification(id)
        } catch (t: Throwable) {
            if (control.paused) {
                store.updateStatusOnly(id, DownloadEntity.STATUS_PAUSED)
                cancelNotification(id)
            } else {
                store.updateStatus(id, DownloadEntity.STATUS_FAILED, t.message)
                notifyFailed(id, output.name, t.message)
            }
        }
    }

    fun resumableTasks(): List<DownloadEntity> = store.pending()

    private fun outputDir(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base ?: appContext.filesDir, "")
        dir.mkdirs()
        return dir
    }

    private fun safeFileName(candidate: MediaCandidate): String {
        val raw = candidate.url.substringAfterLast('/').substringBefore('?')
        val base = raw.ifBlank { "video" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(60)

        val stem = base.substringBeforeLast('.', base)
        val ext = when {
            candidate.url.contains(".m3u8", ignoreCase = true) -> ".mp4"
            candidate.url.contains(".webm", ignoreCase = true) -> ".webm"
            else -> ".mp4"
        }

        // 用 id 前 8 位做后缀，避免同名不同源互相覆盖
        val suffix = candidate.id.take(8)
        return "${stem}_$suffix$ext"
    }

    private fun cookieFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()

    // ---- 通知 ----

    private fun notifyProgress(taskId: String, name: String, percent: Int) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(name)
            .setContentText("下载中 $percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        notificationManager().notify(notifyId(taskId), builder.build())
    }

    private fun notifyDone(taskId: String, name: String, size: Long) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            appContext, notifyId(taskId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(name)
            .setContentText("下载完成 · ${formatSize(size)}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setAutoCancel(true)
        notificationManager().notify(notifyId(taskId), builder.build())
    }

    private fun notifyFailed(taskId: String, name: String, message: String?) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(name)
            .setContentText("下载失败: ${message ?: "未知错误"}")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        notificationManager().notify(notifyId(taskId), builder.build())
    }

    private fun cancelNotification(taskId: String) {
        notificationManager().cancel(notifyId(taskId))
    }

    /** 每个任务一个通知 id，避免多条进度互相覆盖。 */
    private fun notifyId(taskId: String): Int =
        2000 + ((taskId.hashCode() and 0x7FFFFFFF) % 100000)

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知大小"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }

    private fun notificationManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun shutdown() {
        scope.cancel()
        synchronized(lock) {
            queue.clear()
            queued.clear()
            running.clear()
            worker = null
        }
    }

    companion object {
        const val CHANNEL_ID = "kirufetch_downloads"

        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun get(context: Context): DownloadManager =
            INSTANCE ?: synchronized(this) {
                DownloadManager(context).also { INSTANCE = it }
            }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "下载",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
        }
    }
}
