package com.kirufetch.app.download

object HeaderFactory {

    private val blocked = setOf(
        "connection",
        "host",
        "content-length",
        "accept-encoding",
        "te",
        "trailer",
        "upgrade",
        "expect",
        "range",
        // Cookie / Referer 由本类统一重建，不接受页面原值
        "cookie",
        "referer"
    )

    /**
     * 用嗅探时抓到的请求头构造下载请求头。
     *
     * 规则：hop-by-hop 头和 Sec-* 一律丢弃；authorization / origin 只在
     * 目标与页面同源时保留，避免把凭据带到第三方 CDN 上。
     */
    fun build(
        targetUrl: String,
        pageUrl: String?,
        originalHeaders: Map<String, String> = emptyMap(),
        userAgent: String = DEFAULT_UA,
        allowCookies: Boolean = false,
        cookies: String? = null
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val sameOrigin = isSameOrigin(targetUrl, pageUrl)

        originalHeaders.forEach { (key, value) ->
            val lower = key.lowercase()
            when {
                lower in blocked -> return@forEach
                lower.startsWith("sec-fetch") || lower.startsWith("sec-ch-ua") -> return@forEach
                (lower == "authorization" || lower == "origin") && !sameOrigin -> return@forEach
                else -> result[key] = value
            }
        }

        if (result.keys.none { it.equals("user-agent", ignoreCase = true) }) {
            result["User-Agent"] = userAgent
        }

        if (result.keys.none { it.equals("accept", ignoreCase = true) }) {
            result["Accept"] = "*/*"
        }

        if (!pageUrl.isNullOrBlank()) {
            result["Referer"] = pageUrl
        }

        if (allowCookies && !cookies.isNullOrBlank()) {
            result["Cookie"] = cookies
        }

        return result
    }

    private fun isSameOrigin(targetUrl: String, pageUrl: String?): Boolean {
        val targetHost = hostOf(targetUrl) ?: return false
        val pageHost = hostOf(pageUrl) ?: return false
        return targetHost == pageHost
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { java.net.URL(url).host.lowercase() }.getOrNull()
    }

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}
