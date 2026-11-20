package com.kirufetch.app.download

data class HlsVariant(
    val url: String,
    val bandwidth: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val codecs: String? = null,
    val name: String? = null,
    /** EXT-X-STREAM-INF 的 AUDIO 组名，用来把码率和音轨对上 */
    val audioGroup: String? = null
)

data class HlsAudio(
    val url: String,
    val groupId: String? = null,
    val name: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false
)

data class HlsKey(
    val method: String,
    val uri: String?,
    val iv: String? = null,
    val startIndex: Int = 0,
    val endIndex: Int = -1
)

data class HlsInitMap(
    val uri: String,
    val byteRange: String? = null,
    val startIndex: Int = 0,
    val endIndex: Int = -1
)

data class HlsSegment(
    val url: String,
    val duration: Double,
    val byteRange: String? = null
)

data class HlsPlaylist(
    val variants: List<HlsVariant> = emptyList(),
    val audios: List<HlsAudio> = emptyList(),
    val segments: List<HlsSegment> = emptyList(),
    val keys: List<HlsKey> = emptyList(),
    val initMaps: List<HlsInitMap> = emptyList()
)
