package com.kirufetch.app.download

import java.net.URL

object HlsParser {

    fun isMaster(content: String): Boolean = content.contains("#EXT-X-STREAM-INF")

    fun parse(baseUrl: String, content: String): HlsPlaylist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudio>()
        val segments = mutableListOf<HlsSegment>()
        val keys = mutableListOf<HlsKey>()
        val initMaps = mutableListOf<HlsInitMap>()

        var pendingStreamInfo: Map<String, String>? = null
        var pendingDuration: Double? = null
        var pendingByteRange: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    pendingStreamInfo = parseAttributes(line.substringAfter(":"))
                }

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttributes(line.substringAfter(":"))
                    val uri = a["URI"]
                    if (!uri.isNullOrBlank()) {
                        audios += HlsAudio(
                            url = resolve(baseUrl, uri),
                            groupId = a["GROUP-ID"],
                            name = a["NAME"],
                            language = a["LANGUAGE"],
                            isDefault = a["DEFAULT"] == "YES"
                        )
                    }
                }

                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(":").substringBefore(",").toDoubleOrNull()
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingByteRange = line.substringAfter(":")
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttributes(line.substringAfter(":"))
                    val uri = a["URI"]
                    if (!uri.isNullOrBlank()) {
                        initMaps += HlsInitMap(resolve(baseUrl, uri), a["BYTERANGE"], startIndex = segments.size)
                    }
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val a = parseAttributes(line.substringAfter(":"))
                    val method = a["METHOD"] ?: "NONE"
                    if (method != "NONE") {
                        keys += HlsKey(
                            method = method,
                            uri = a["URI"]?.let { resolve(baseUrl, it) },
                            iv = a["IV"],
                            startIndex = segments.size
                        )
                    }
                }

                !line.startsWith("#") -> {
                    val segmentUrl = resolve(baseUrl, line)
                    val info = pendingStreamInfo
                    if (info != null) {
                        val resolution = info["RESOLUTION"]
                        variants += HlsVariant(
                            url = segmentUrl,
                            bandwidth = info["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                            width = resolution?.substringBefore("x")?.toIntOrNull() ?: 0,
                            height = resolution?.substringAfter("x")?.toIntOrNull() ?: 0,
                            codecs = info["CODECS"],
                            audioGroup = info["AUDIO"]
                        )
                        pendingStreamInfo = null
                    } else {
                        segments += HlsSegment(
                            url = segmentUrl,
                            duration = pendingDuration ?: 0.0,
                            byteRange = pendingByteRange
                        )
                        pendingDuration = null
                        pendingByteRange = null
                    }
                }
            }
        }

        for (i in keys.indices) {
            if (keys[i].endIndex < 0) {
                val next = keys.getOrNull(i + 1)?.startIndex ?: segments.size
                keys[i] = keys[i].copy(endIndex = next - 1)
            }
        }

        for (i in initMaps.indices) {
            if (initMaps[i].endIndex < 0) {
                val next = initMaps.getOrNull(i + 1)?.startIndex ?: segments.size
                initMaps[i] = initMaps[i].copy(endIndex = next - 1)
            }
        }

        return HlsPlaylist(variants, audios, segments, keys, initMaps)
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var key: String? = null
        var inQuotes = false

        fun flush() {
            val k = key
            if (k != null) {
                result[k] = sb.toString().trim().trim('"')
            }
            sb.setLength(0)
            key = null
        }

        for (ch in raw) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                ch == '=' && !inQuotes && key == null -> {
                    key = sb.toString().trim()
                    sb.setLength(0)
                }
                ch == ',' && !inQuotes -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return result
    }

    private fun resolve(baseUrl: String, ref: String): String {
        return runCatching { URL(URL(baseUrl), ref).toString() }.getOrDefault(ref)
    }
}
