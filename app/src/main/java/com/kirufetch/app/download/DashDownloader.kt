package com.kirufetch.app.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * DASH 下载：拿最高码率的视频轨 + 音频轨，下 init 与分片，拼好后交给 MediaMuxer 合轨。
 * 分片顺序下载（DASH 分片普遍不大，先保证正确性）。
 */
class DashDownloader(
    private val client: OkHttpClient = OkHttpClient()
) {

    suspend fun download(
        mpdUrl: String,
        headers: Map<String, String>,
        output: File,
        control: DownloadControl,
        onProgress: (done: Int, total: Int) -> Unit
    ): Result<DashResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val xml = getTextWithRetry(mpdUrl, headers, control)
            val manifest = DashParser.parse(mpdUrl, xml)

            val video = manifest.video.maxByOrNull { it.bandwidth }
                ?: error("MPD 里没有可用的视频轨")
            val audio = manifest.audio.maxByOrNull { it.bandwidth }

            val workDir = File(output.parentFile, "${output.name}.parts")
            workDir.mkdirs()

            val videoFile = File(workDir, "video.bin")
            downloadRepresentation(video, headers, videoFile, control, onProgress)

            var audioFile: File? = null
            if (audio != null) {
                control.checkpoint()
                val f = File(workDir, "audio.bin")
                downloadRepresentation(audio, headers, f, control, null)
                audioFile = f
            }

            control.checkpoint()

            var audioPath: String? = null
            var muxed = false
            if (audioFile != null) {
                val muxedFile = File(output.parentFile, output.nameWithoutExtension + ".muxed.mp4")
                if (MediaMuxerHelper.mux(videoFile, audioFile, muxedFile)) {
                    moveTo(muxedFile, output)
                    muxed = true
                }
            }

            if (!muxed) {
                moveTo(videoFile, output)
                if (audioFile != null) {
                    val keep = File(output.parentFile, output.nameWithoutExtension + ".audio.bin")
                    moveTo(audioFile, keep)
                    audioPath = keep.absolutePath
                }
            }

            workDir.deleteRecursively()
            DashResult(output, audioPath)
        }
    }

    private suspend fun downloadRepresentation(
        rep: DashParser.Representation,
        headers: Map<String, String>,
        target: File,
        control: DownloadControl,
        onProgress: ((Int, Int) -> Unit)?
    ) {
        val total = rep.segments.size
        var done = 0

        FileOutputStream(target).use { out ->
            rep.init?.let { init ->
                control.checkpoint()
                out.write(getBytesWithRetry(init.url, headers, init.range, control))
            }
            rep.segments.forEach { segment ->
                control.checkpoint()
                out.write(getBytesWithRetry(segment.url, headers, segment.range, control))
                done++
                onProgress?.invoke(done, total)
            }
        }
    }

    private suspend fun getTextWithRetry(
        url: String,
        headers: Map<String, String>,
        control: DownloadControl
    ): String {
        var attempt = 0
        while (true) {
            control.checkpoint()
            try {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $url")
                    return response.body?.string() ?: error("MPD 为空")
                }
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

    private suspend fun getBytesWithRetry(
        url: String,
        headers: Map<String, String>,
        range: String?,
        control: DownloadControl
    ): ByteArray {
        var attempt = 0
        while (true) {
            control.checkpoint()
            try {
                val builder = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }
                if (!range.isNullOrBlank()) builder.header("Range", "bytes=$range")

                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $url")
                    return response.body?.bytes() ?: error("空响应：$url")
                }
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

    private fun moveTo(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (src.renameTo(dst)) return
        src.inputStream().use { input ->
            FileOutputStream(dst).use { input.copyTo(it) }
        }
        src.delete()
    }

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 400L
    }
}

data class DashResult(
    val file: File,
    val audioPath: String?
)
