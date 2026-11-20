package com.kirufetch.app.data

enum class SearchEngine(val label: String, val queryPrefix: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BAIDU("百度", "https://www.baidu.com/s?wd=");

    fun buildUrl(query: String): String =
        queryPrefix + java.net.URLEncoder.encode(query, "UTF-8")
}
