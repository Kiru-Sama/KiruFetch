package com.kirufetch.app.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

class DirectDownloader(
    private val client: OkHttpClient = OkHttpClient()
) {

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: File,
        threads: Int,
        control: DownloadControl,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val probe = probe(url, headers)
            val total = probe.totalBytes
            val useRange = probe.rangeSupported && total > MIN_SEGMENTED_SIZE && threads > 1

            if (useRange) {
                downloadSegmented(url, headers, output, total, threads.coerceIn(1, 16), control, onProgress)
            } else {
                downloadSingle(url, headers, output, control, onProgress)
            }
        }
    }

    private suspend fun downloadSegmented(
        url: String,
        headers: Map<String, String>,
        output: File,
        total: Long,
        threads: Int,
        control: DownloadControl,
        onProgress: (Long, Long) -> Unit
    ): File {
        val partSize = (total + threads - 1) / threads
        val parts = (0 until threads).map { index ->
            val start = index * partSize
            val end = minOf(start + partSize - 1, total - 1)
            Part(index, start, end, File(output.absolutePath + ".part$index"))
        }.filter { it.end >= it.start }

        val done = AtomicLong(parts.sumOf { it.file.length().coerceAtMost(it.length) })
        onProgress(done.get(), total)

        coroutineScope {
            parts.map { part ->
                async(Dispatchers.IO) {
                    downloadPart(url, headers, part, control, total, done, onProgress)
                }
            }.awaitAll()
        }

        control.checkpoint()
        // 合并前逐片核对长度：短一截拼进去就是坏文件，不能当成功
        val broken = parts.firstOrNull { it.file.length() != it.length }
        if (broken != null) {
            error("分片 ${broken.index} 不完整：${broken.file.length()}/${broken.length}")
        }

        merge(parts, output)
        parts.forEach { it.file.delete() }
        onProgress(total, total)
        return output
    }

    private suspend fun downloadPart(
        url: String,
        headers: Map<String, String>,
        part: Part,
        control: DownloadControl,
        total: Long,
        done: AtomicLong,
        onProgress: (Long, Long) -> Unit
    ) {
        var attempt = 0
        while (true) {
            control.checkpoint()
            try {
                transferPart(url, headers, part, control, total, done, onProgress)
                if (part.file.length() == part.length) return
                if (part.file.length() > part.length) part.file.delete()
                error("分片 ${part.index} 未下满：${part.file.length()}/${part.length}")
            } catch (e: PausedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (attempt >= MAX_RETRY) throw t
            }
            attempt++
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    private fun transferPart(
        url: String,
        headers: Map<String, String>,
        part: Part,
        control: DownloadControl,
        total: Long,
        done: AtomicLong,
        onProgress: (Long, Long) -> Unit
    ) {
        var offset = part.file.length()
        if (offset > part.length) {
            part.file.delete()
            offset = 0
        }
        if (offset >= part.length) return

        val start = part.start + offset
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$start-${part.end}")
            .build()

        client.newCall(request).execute().use { response ->
            // 分片请求被服务器忽略 Range、直接回 200 整包时，续写会拼错位
            if (response.code == 200 && start > 0) error("服务器忽略了 Range，无法分片续传")
            if (response.code != 206 && response.code != 200) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            FileOutputStream(part.file, true).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        control.checkpoint()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done.addAndGet(read.toLong())
                        onProgress(done.get(), total)
                    }
                }
            }
        }
    }

    private suspend fun downloadSingle(
        url: String,
        headers: Map<String, String>,
        output: File,
        control: DownloadControl,
        onProgress: (Long, Long) -> Unit
    ): File {
        val part = File(output.absolutePath + ".part")
        var attempt = 0

        while (true) {
            control.checkpoint()
            try {
                val total = transferSingle(url, headers, part, control, onProgress)
                control.checkpoint()
                if (total > 0 && part.length() != total) {
                    error("文件不完整：${part.length()}/$total")
                }
                moveTo(part, output)
                return output
            } catch (e: PausedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (attempt >= MAX_RETRY) throw t
            }
            attempt++
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** 返回文件总大小，未知时返回 -1。 */
    private fun transferSingle(
        url: String,
        headers: Map<String, String>,
        part: File,
        control: DownloadControl,
        onProgress: (Long, Long) -> Unit
    ): Long {
        var offset = part.length()

        val builder = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            if (offset > 0 && response.code != 206) {
                offset = 0
                part.delete()
            }
            val body = response.body ?: error("Empty body")
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) contentLength + offset else -1L

            FileOutputStream(part, offset > 0).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var now = offset
                    while (true) {
                        control.checkpoint()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        now += read
                        onProgress(now, total)
                    }
                }
            }
            return total
        }
    }

    private fun moveTo(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (src.renameTo(dst)) return
        val buffer = ByteArray(128 * 1024)
        src.inputStream().use { input ->
            FileOutputStream(dst).use { out ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        src.delete()
    }

    private fun merge(parts: List<Part>, output: File) {
        output.parentFile?.mkdirs()
        RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            val buffer = ByteArray(128 * 1024)
            parts.forEach { part ->
                part.file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun probe(url: String, headers: Map<String, String>): Probe {
        val builder = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0")

        client.newCall(builder.build()).execute().use { response ->
            val rangeHeader = response.header("Accept-Ranges")
            val contentRange = response.header("Content-Range")
            val total = contentRange?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: -1L
            val supported = response.code == 206 ||
                    rangeHeader?.contains("bytes", ignoreCase = true) == true
            return Probe(total, supported)
        }
    }

    private data class Probe(val totalBytes: Long, val rangeSupported: Boolean)

    private data class Part(
        val index: Int,
        val start: Long,
        val end: Long,
        val file: File
    ) {
        val length: Long get() = end - start + 1
    }

    companion object {
        private const val MIN_SEGMENTED_SIZE = 2 * 1024 * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 400L
    }
}
