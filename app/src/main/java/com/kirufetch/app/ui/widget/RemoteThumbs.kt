package com.kirufetch.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.kirufetch.app.download.HeaderFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 网页封面（og:image / video poster）的加载器。
 * 和本地视频封面分开：这里走网络，带内存 + 磁盘缓存，失败就返回 null 让 UI 显示占位。
 */
object RemoteThumbs {

    private val mem = LruCache<String, Bitmap>(24)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private const val WIDTH = 320

    fun load(context: Context, url: String, onReady: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            onReady(null)
            return
        }
        mem.get(url)?.let { onReady(it); return }
        val app = context.applicationContext
        pool.execute {
            val bmp = runCatching { readOrFetch(app, url) }.getOrNull()
            main.post {
                if (bmp != null) mem.put(url, bmp)
                onReady(bmp)
            }
        }
    }

    private fun readOrFetch(app: Context, url: String): Bitmap? {
        val dir = File(app.cacheDir, "posters").apply { mkdirs() }
        val cached = File(dir, url.hashCode().toUInt().toString(16) + ".jpg")
        if (cached.exists() && cached.length() > 0) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HeaderFactory.DEFAULT_UA)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = if (bitmap.width > WIDTH) {
                val h = (bitmap.height * (WIDTH.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, WIDTH, h, true)
            } else bitmap
            runCatching {
                cached.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 84, out)
                }
            }
            return scaled
        }
    }
}
