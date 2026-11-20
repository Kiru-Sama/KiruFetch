package com.kirufetch.app.sniff

object MediaDetector {

    private val videoExtensions = listOf(
        ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".flv", ".ts", ".m3u8", ".mpd"
    )

    fun kindOf(url: String, mime: String?): MediaCandidate.Kind? {
        val lowerUrl = url.lowercase()
        val lowerMime = mime?.lowercase()

        if (lowerUrl.contains(".m3u8") ||
            lowerMime == "application/vnd.apple.mpegurl" ||
            lowerMime == "application/x-mpegurl"
        ) {
            return MediaCandidate.Kind.HLS
        }

        if (lowerUrl.contains(".mpd") || (lowerMime != null && lowerMime.contains("dash"))) {
            return MediaCandidate.Kind.DASH
        }

        if (lowerUrl.contains(".mp4") || lowerMime == "video/mp4") {
            return MediaCandidate.Kind.MP4
        }

        if (lowerUrl.contains(".webm") || lowerMime == "video/webm") {
            return MediaCandidate.Kind.WEBM
        }

        if (lowerMime != null && lowerMime.startsWith("video/")) {
            return MediaCandidate.Kind.UNKNOWN_VIDEO
        }

        if (lowerMime != null && lowerMime.startsWith("audio/")) {
            return MediaCandidate.Kind.AUDIO
        }

        if (videoExtensions.any { lowerUrl.contains(it) }) {
            return MediaCandidate.Kind.UNKNOWN_VIDEO
        }

        return null
    }

    fun isMediaUrl(url: String, mime: String?): Boolean = kindOf(url, mime) != null
}
