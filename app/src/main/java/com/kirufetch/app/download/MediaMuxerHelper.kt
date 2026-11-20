package com.kirufetch.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 把分离的视频轨和音频轨重新封装成一个容器文件。
 * 输入可以是 MPEG-TS 或 fMP4，输出为 MP4。
 */
object MediaMuxerHelper {

    fun mux(video: File, audio: File, output: File): Boolean {
        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0 && audioTrack < 0) return false

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoOut = if (videoTrack >= 0) {
                val format = videoExtractor.getTrackFormat(videoTrack)
                muxer.addTrack(format)
            } else -1

            val audioOut = if (audioTrack >= 0) {
                val format = audioExtractor.getTrackFormat(audioTrack)
                muxer.addTrack(format)
            } else -1

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            if (videoTrack >= 0 && videoOut >= 0) {
                copyTrack(videoExtractor, videoTrack, muxer, videoOut, buffer, info)
            }
            if (audioTrack >= 0 && audioOut >= 0) {
                copyTrack(audioExtractor, audioTrack, muxer, audioOut, buffer, info)
            }

            muxer.stop()
            return true
        } catch (t: Throwable) {
            runCatching { muxer?.stop() }
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    /**
     * 单文件重封装：把 MPEG-TS 之类换成真正的 MP4 容器（音轨已经在同一个文件里）。
     * HLS 里没有独立音轨时走这条路，避免交付一个后缀是 .mp4 的 TS。
     */
    fun remux(input: File, output: File): Boolean {
        var muxer: MediaMuxer? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)

            val trackMap = LinkedHashMap<Int, Int>()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) return false

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            for ((srcIndex, outIndex) in trackMap) {
                extractor.selectTrack(srcIndex)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outIndex, buffer, info)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(srcIndex)
            }

            muxer.stop()
            return true
        } catch (t: Throwable) {
            runCatching { muxer?.stop() }
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                extractor.selectTrack(i)
                return i
            }
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        extractor.selectTrack(track)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }
}
