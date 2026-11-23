package com.kirufetch.app.sniff

data class MediaCandidate(
    val id: String,
    val url: String,
    val pageUrl: String?,
    val headers: Map<String, String> = emptyMap(),
    val kind: Kind = Kind.UNKNOWN,
    val mimeType: String? = null,
    val fileName: String? = null,
    /** 页面封面（og:image / video poster），候选列表用来显示缩略图 */
    val posterUrl: String? = null
) {
    enum class Kind {
        MP4, WEBM, HLS, DASH, AUDIO, UNKNOWN_VIDEO, UNKNOWN
    }

    val isPlaylist: Boolean
        get() = kind == Kind.HLS || kind == Kind.DASH

    val label: String
        get() = when (kind) {
            Kind.MP4 -> "MP4"
            Kind.WEBM -> "WebM"
            Kind.HLS -> "HLS"
            Kind.DASH -> "DASH"
            Kind.AUDIO -> "音频"
            Kind.UNKNOWN_VIDEO -> "视频"
            Kind.UNKNOWN -> "未知"
        }

    companion object {
        /** 从库里存的 kind 字符串还原，冷启动续传要用。 */
        fun fromStored(kind: String): Kind = when (kind.uppercase()) {
            "HLS" -> Kind.HLS
            "DASH" -> Kind.DASH
            "WEBM" -> Kind.WEBM
            "AUDIO" -> Kind.AUDIO
            "MP4" -> Kind.MP4
            else -> Kind.MP4
        }
    }
}
