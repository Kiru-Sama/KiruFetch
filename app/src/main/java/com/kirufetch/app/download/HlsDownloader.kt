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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloader(
    private val client: OkHttpClient = OkHttpClient()
) {

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        output: File,
        threads: Int,
        control: DownloadControl,
        variantUrl: String? = null,
        onProgress: (done: Int, total: Int) -> Unit
    ): Result<HlsResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val firstText = getTextWithRetry(playlistUrl, headers, control)
            val master = if (HlsParser.isMaster(firstText)) {
                HlsParser.parse(playlistUrl, firstText)
            } else null

            val mediaUrl: String
            val mediaText: String
            when {
                variantUrl != null && variantUrl != playlistUrl -> {
                    mediaUrl = variantUrl
                    mediaText = getTextWithRetry(mediaUrl, headers, control)
                }

                variantUrl != null -> {
                    mediaUrl = playlistUrl
                    mediaText = firstText
                }

                master != null -> {
                    val best = master.variants.maxByOrNull { it.bandwidth }
                        ?: error("master playlist 里没有可用清晰度")
                    mediaUrl = best.url
                    mediaText = getTextWithRetry(mediaUrl, headers, control)
                }

                else -> {
                    mediaUrl = playlistUrl
                    mediaText = firstText
                }
            }

            val media = HlsParser.parse(mediaUrl, mediaText)
            require(media.segments.isNotEmpty()) { "media playlist 里没有分片" }

            // EXT-X-MEDIA 只出现在 master。选了清晰度时 variantUrl 会跳过 master 解析，
            // 所以音轨必须单独从 master 带过来，否则成品是无声的。
            val audioGroup = master?.variants?.firstOrNull { it.url == mediaUrl }?.audioGroup
            val audioTracks = if (media.audios.isNotEmpty()) media.audios else master?.audios.orEmpty()
            val audioTrack = pickAudio(audioTracks, audioGroup)

            val workDir = File(output.parentFile, "${output.name}.parts")
            workDir.mkdirs()

            val keyCache = ConcurrentHashMap<String, ByteArray>()
            val segmentFiles = downloadSegments(
                media, headers, workDir, threads.coerceIn(1, 8), control, keyCache, onProgress
            )
            val audioFile = audioTrack?.let { downloadAudio(it, headers, workDir, control, keyCache) }
            onProgress(media.segments.size, media.segments.size)

            control.checkpoint()

            val videoOnly = File(workDir, "video.bin")
            mergeSegments(segmentFiles, videoOnly)

            var audioPath: String? = null
            var muxed = false
            if (audioFile != null) {
                val muxedFile = File(output.parentFile, output.nameWithoutExtension + ".muxed.mp4")
                if (MediaMuxerHelper.mux(videoOnly, audioFile, muxedFile)) {
                    moveTo(muxedFile, output)
                    muxed = true
                }
            }

            if (!muxed) {
                // 没有独立音轨时整段还是 MPEG-TS，重新封装成真正的 MP4，
                // 否则会交付一个后缀是 .mp4 的 TS 文件
                val remixed = File(output.parentFile, output.nameWithoutExtension + ".remux.mp4")
                if (MediaMuxerHelper.remux(videoOnly, remixed)) {
                    moveTo(remixed, output)
                    muxed = true
                }
            }

            if (!muxed) {
                moveTo(videoOnly, output)
                // 合轨失败时保留音轨，素材至少不丢
                if (audioFile != null) {
                    val keep = File(output.parentFile, output.nameWithoutExtension + ".audio.bin")
                    moveTo(audioFile, keep)
                    audioPath = keep.absolutePath
                }
            }

            workDir.deleteRecursively()

            HlsResult(output, audioPath)
        }
    }

    private fun pickAudio(tracks: List<HlsAudio>, group: String?): HlsAudio? =
        tracks.firstOrNull { it.groupId == group && it.isDefault }
            ?: tracks.firstOrNull { it.groupId == group }
            ?: tracks.firstOrNull { it.isDefault }
            ?: tracks.firstOrNull()

    private suspend fun downloadSegments(
        media: HlsPlaylist,
        headers: Map<String, String>,
        workDir: File,
        threads: Int,
        control: DownloadControl,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        onProgress: (Int, Int) -> Unit
    ): List<File> {
        val total = media.segments.size
        val results = arrayOfNulls<File>(total)
        val ranges = resolveRanges(media.segments)

        // 续传时先把已下好的分片计入进度，否则进度会从 0% 重新爬
        var existing = 0
        for (i in 0 until total) {
            val f = File(workDir, "seg_%05d.ts".format(i))
            if (f.exists() && f.length() > 0) existing++
        }
        val done = AtomicInteger(existing)
        if (existing > 0) onProgress(existing, total)

        val initFiles = downloadInitMaps(media, headers, workDir, control, keyCache)

        val chunks = (0 until total).chunked(threads)
        for (chunk in chunks) {
            control.checkpoint()
            coroutineScope {
                chunk.map { index ->
                    async(Dispatchers.IO) {
                        val segment = media.segments[index]
                        val segmentFile = File(workDir, "seg_%05d.ts".format(index))
                        if (segmentFile.exists() && segmentFile.length() > 0) {
                            // 已在前面统一计数，这里只登记结果
                            results[index] = segmentFile
                            return@async
                        }

                        var bytes = getBytesWithRetry(segment.url, headers, ranges[index], control)

                        val key = media.keys.lastOrNull { index in it.startIndex..it.endIndex }
                        if (key != null && key.method.equals("AES-128", ignoreCase = true)) {
                            val keyBytes = key.uri?.let { uri ->
                                keyCache.getOrPut(uri) {
                                    getBytesWithRetry(uri, headers, null, control)
                                }
                            }
                            if (keyBytes != null) {
                                bytes = decryptAes(bytes, keyBytes, key.iv, index)
                            }
                        }

                        FileOutputStream(segmentFile).use { it.write(bytes) }
                        results[index] = segmentFile
                        done.incrementAndGet()
                        onProgress(done.get(), total)
                    }
                }.awaitAll()
            }
        }

        val merged = mutableListOf<File>()
        merged += initFiles
        merged += results.filterNotNull()
        return merged
    }

    private suspend fun downloadInitMaps(
        media: HlsPlaylist,
        headers: Map<String, String>,
        workDir: File,
        control: DownloadControl,
        keyCache: ConcurrentHashMap<String, ByteArray>
    ): List<File> {
        if (media.initMaps.isEmpty()) return emptyList()
        val out = mutableListOf<File>()

        for ((order, map) in media.initMaps.withIndex()) {
            val target = File(workDir, "init_%03d.mp4".format(order))
            if (target.exists() && target.length() > 0) {
                out += target
                continue
            }
            var bytes = getBytesWithRetry(map.uri, headers, parseRange(map.byteRange, 0L), control)
            val key = media.keys.lastOrNull { map.startIndex in it.startIndex..it.endIndex }
            if (key != null && key.method.equals("AES-128", ignoreCase = true) && key.uri != null) {
                val keyBytes = keyCache.getOrPut(key.uri) {
                    getBytesWithRetry(key.uri, headers, null, control)
                }
                bytes = runCatching { decryptAes(bytes, keyBytes, key.iv) }.getOrDefault(bytes)
            }
            FileOutputStream(target).use { it.write(bytes) }
            out += target
        }
        return out
    }

    private suspend fun downloadAudio(
        track: HlsAudio,
        headers: Map<String, String>,
        workDir: File,
        control: DownloadControl,
        keyCache: ConcurrentHashMap<String, ByteArray>
    ): File? {
        return try {
            val text = getTextWithRetry(track.url, headers, control)
            val playlist = HlsParser.parse(track.url, text)
            if (playlist.segments.isEmpty()) return null

            val target = File(workDir, "audio_track.bin")
            val ranges = resolveRanges(playlist.segments)

            FileOutputStream(target).use { out ->
                // fMP4 音轨必须按 init + 分片 的顺序拼
                playlist.initMaps.forEach { map ->
                    control.checkpoint()
                    out.write(
                        getBytesWithRetry(map.uri, headers, parseRange(map.byteRange, 0L), control)
                    )
                }
                playlist.segments.forEachIndexed { index, segment ->
                    control.checkpoint()
                    var bytes = getBytesWithRetry(segment.url, headers, ranges[index], control)
                    val key = playlist.keys.lastOrNull { index in it.startIndex..it.endIndex }
                    if (key != null && key.method.equals("AES-128", ignoreCase = true)) {
                        val keyBytes = key.uri?.let { uri ->
                            keyCache.getOrPut(uri) { getBytesWithRetry(uri, headers, null, control) }
                        }
                        if (keyBytes != null) bytes = decryptAes(bytes, keyBytes, key.iv, index)
                    }
                    out.write(bytes)
                }
            }
            target
        } catch (e: PausedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 音轨挂掉不该让整条视频失败，但暂停/取消必须照常上抛
            null
        }
    }

    private fun mergeSegments(files: List<File>, output: File) {
        output.parentFile?.mkdirs()
        RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            val buffer = ByteArray(128 * 1024)
            files.forEach { f ->
                f.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun moveTo(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { input.copyTo(it) }
            }
            src.delete()
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
                return getText(url, headers)
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
        range: LongRange?,
        control: DownloadControl
    ): ByteArray {
        var attempt = 0
        while (true) {
            control.checkpoint()
            try {
                return getBytes(url, headers, range)
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

    private fun getText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string() ?: error("Empty body for $url")
        }
    }

    private fun getBytes(url: String, headers: Map<String, String>, range: LongRange?): ByteArray {
        val builder = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (range != null) {
            builder.header("Range", "bytes=${range.first}-${range.last}")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: error("Empty body for $url")
        }
    }

    /**
     * EXT-X-BYTERANGE 只有在 offset 省略时才接上一段的末尾，不是从 0 开始。
     * 这里按播放顺序预先把所有子范围换算成绝对区间。
     */
    private fun resolveRanges(segments: List<HlsSegment>): List<LongRange?> {
        var next = 0L
        return segments.map { segment ->
            val raw = segment.byteRange
            if (raw.isNullOrBlank()) {
                null
            } else {
                parseRange(raw, next)?.also { next = it.last + 1 }
            }
        }
    }

    private fun parseRange(raw: String?, defaultStart: Long): LongRange? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("@")
        val length = parts[0].trim().toLongOrNull() ?: return null
        if (length <= 0) return null
        val start = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: defaultStart
        return start..(start + length - 1)
    }

    /**
     * AES-128 按 RFC 8216 是 PKCS7 填充，但真实站点里无填充的也不少。
     * 先按带填充解，抛 BadPadding 再回退到 NoPadding。
     */
    private fun decryptAes(
        data: ByteArray,
        keyBytes: ByteArray,
        ivHex: String?,
        sequence: Int = 0
    ): ByteArray {
        val spec = SecretKeySpec(keyBytes, "AES")
        val iv = if (!ivHex.isNullOrBlank()) {
            parseHex(ivHex.removePrefix("0x"))
        } else {
            val b = ByteArray(16)
            var v = sequence.toLong()
            for (i in 15 downTo 8) {
                b[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            b
        }

        val padded = runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
            }.doFinal(data)
        }
        if (padded.isSuccess) return padded.getOrThrow()

        return Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
        }.doFinal(data)
    }

    private fun parseHex(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 400L
    }
}

data class HlsResult(
    val file: File,
    val audioPath: String?
)
