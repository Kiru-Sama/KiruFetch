package com.kirufetch.app.sniff

import android.webkit.JavascriptInterface

class MediaBridge(
    private val onUrls: (List<String>) -> Unit,
    private val onPoster: (String) -> Unit = {}
) {
    @JavascriptInterface
    fun onPagePoster(url: String) {
        if (url.isNotBlank()) onPoster(url)
    }

    @JavascriptInterface
    fun onVideoUrls(json: String) {
        runCatching {
            val trimmed = json.trim().trim('"')
            val urls = trimmed
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
            onUrls(urls)
        }
    }
}
