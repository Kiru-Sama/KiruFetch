package com.kirufetch.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * 下载列表的封面：从成品视频里抽一帧，内存 + 磁盘两级缓存。
 * 抽帧在后台线程做，主线程只拿缓存或等回调。
 */
object VideoThumbs {

    private val mem = LruCache<String, Bitmap>(32)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private const val WIDTH = 320

    fun load(context: Context, key: String, path: String, onReady: (Bitmap?) -> Unit) {
        mem.get(key)?.let { onReady(it); return }
        val app = context.applicationContext
        pool.execute {
            val bmp = runCatching { readOrExtract(app, key, path) }.getOrNull()
            main.post {
                if (bmp != null) mem.put(key, bmp)
                onReady(bmp)
            }
        }
    }

    private fun readOrExtract(app: Context, key: String, path: String): Bitmap? {
        val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(dir, "$key-v3.jpg")
        if (cached.exists() && cached.length() > 0) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }

        val retriever = MediaMetadataRetriever()
        val frame = try {
            if (path.startsWith("content://")) {
                retriever.setDataSource(app, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            // 多抽几帧，跳过黑场，挑细节最多的一张当封面
            var best: Bitmap? = null
            var bestScore = -1.0
            for (timeUs in longArrayOf(3_000_000, 6_000_000, 10_000_000, 15_000_000, 1_500_000)) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val stats = statsOf(frame)
                if (stats.first <= 26) continue
                if (stats.second > bestScore) {
                    bestScore = stats.second
                    best = frame
                }
            }
            best ?: retriever.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        } ?: return null

        val scaled = scaleToWidth(frame, WIDTH)
        runCatching {
            cached.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
            }
        }
        return scaled
    }

    /** 返回 (平均亮度, 细节强度)，细节用亮度标准差近似。 */
    private fun statsOf(bmp: Bitmap): Pair<Int, Double> {
        val small = Bitmap.createScaledBitmap(bmp, 24, 14, false)
        val values = ArrayList<Int>(small.width * small.height)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val p = small.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                values.add((r * 299 + g * 587 + b * 114) / 1000)
            }
        }
        if (small !== bmp) small.recycle()
        if (values.isEmpty()) return 0 to 0.0
        val avg = values.average()
        val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
        return avg.toInt() to kotlin.math.sqrt(variance)
    }

    private fun scaleToWidth(src: Bitmap, width: Int): Bitmap {
        if (src.width <= width) return src
        val height = (src.height * (width.toFloat() / src.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, height, true)
    }

    fun clearFor(key: String) {
        mem.remove(key)
    }
}
