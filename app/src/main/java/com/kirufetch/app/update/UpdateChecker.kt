package com.kirufetch.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
)

/**
 * 从 GitHub Releases 取最新版本。仓库是公开的，不需要 token。
 * 走 API 有 60 次/小时的匿名限额，够用。
 */
object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/Kiru-Sama/KiruFetch/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KiruFetch-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val notes = json.optString("body").trim()

                var apkUrl = ""
                var apkSize = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").endsWith(".apk", true)) {
                            apkUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size")
                            break
                        }
                    }
                }
                if (tag.isBlank() || apkUrl.isBlank()) return@withContext null
                UpdateInfo(tag, notes, apkUrl, apkSize)
            }
        }.getOrNull()
    }

    /** 1.2.0 > 1.1.9；本地带 -debug 后缀也能比较。 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = numbers(remote)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.take(3).toList()
}
