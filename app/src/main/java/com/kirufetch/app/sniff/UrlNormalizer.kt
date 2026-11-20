package com.kirufetch.app.sniff

import java.security.MessageDigest

object UrlNormalizer {
    private val excludedHosts = listOf(
        "youtube.com", "www.youtube.com", "youtu.be",
        "facebook.com", "www.facebook.com",
        "instagram.com", "www.instagram.com"
    )

    /**
     * 用于去重和哈希的键：去掉 query、fragment、末尾斜杠。
     * 不做小写化，避免破坏大小写敏感的 CDN 路径。
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val h = s.indexOf('#')
        if (h >= 0) s = s.substring(0, h)
        while (s.endsWith("/") && s.length > "https://a".length) s = s.dropLast(1)
        return s
    }

    /** 真正用于下载的 URL：只去掉 fragment，保留原始大小写和 query。 */
    fun normalizeForDownload(raw: String): String {
        val s = raw.trim()
        val h = s.indexOf('#')
        return if (h >= 0) s.substring(0, h) else s
    }

    fun isCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return false
        if (excludedHosts.any { host == it || host.endsWith(".$it") }) return false
        return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m3u8") ||
                lower.contains(".mpd") || lower.contains("video/") || lower.contains("octet-stream")
    }

    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
