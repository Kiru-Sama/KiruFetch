package com.kirufetch.app.download

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在真正开始下载前，先把 master playlist 里的清晰度列出来给用户挑。
 */
class HlsProbe(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun variants(
        playlistUrl: String,
        headers: Map<String, String>
    ): Result<List<HlsVariant>> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val text = getText(playlistUrl, headers)
            if (!HlsParser.isMaster(text)) return@runCatchingCancellable emptyList()
            val master = HlsParser.parse(playlistUrl, text)
            master.variants.sortedByDescending { it.bandwidth }
        }
    }

    private fun getText(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("空响应")
        }
    }
}
