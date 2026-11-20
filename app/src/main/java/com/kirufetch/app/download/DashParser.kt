package com.kirufetch.app.download

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MPEG-DASH 的 MPD 解析。只做 VOD 需要的那一档：
 * SegmentTemplate（含 SegmentTimeline / duration）、SegmentList、单文件 BaseURL。
 */
object DashParser {

    data class Segment(val url: String, val range: String? = null)

    data class Representation(
        val id: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val codecs: String?,
        val init: Segment?,
        val segments: List<Segment>
    )

    data class Manifest(val video: List<Representation>, val audio: List<Representation>)

    fun parse(mpdUrl: String, xml: String): Manifest {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // MPD 来自网络，关掉 DTD，免得 XXE
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        } catch (t: Throwable) {
            error("MPD 解析失败：${t.message}")
        }

        val root = doc.documentElement ?: error("MPD 为空")
        val mpdBase = resolve(mpdUrl, directChild(root, "BaseURL")?.textContent?.trim())
        val mpdDuration = parseIsoDuration(root.getAttribute("mediaPresentationDuration"))

        val video = mutableListOf<Representation>()
        val audio = mutableListOf<Representation>()

        val periods = childElements(root, "Period").ifEmpty { listOf(root) }
        for (period in periods) {
            val periodBase = resolve(mpdBase, directChild(period, "BaseURL")?.textContent?.trim())
            val periodDuration = parseIsoDuration(period.getAttribute("mediaPresentationDuration"))
                .takeIf { it > 0 } ?: mpdDuration

            val periodTemplate = directChild(period, "SegmentTemplate")
            val periodList = directChild(period, "SegmentList")
            val periodBaseSeg = directChild(period, "SegmentBase")

            for (adaptation in childElements(period, "AdaptationSet")) {
                val adaptationBase = resolve(
                    periodBase, directChild(adaptation, "BaseURL")?.textContent?.trim()
                )
                val adaptationMime = adaptation.getAttribute("mimeType").takeIf { it.isNotBlank() }
                val contentType = adaptation.getAttribute("contentType").takeIf { it.isNotBlank() }

                val template = directChild(adaptation, "SegmentTemplate") ?: periodTemplate
                val segmentList = directChild(adaptation, "SegmentList") ?: periodList
                val segmentBase = directChild(adaptation, "SegmentBase") ?: periodBaseSeg

                for (rep in childElements(adaptation, "Representation")) {
                    val repId = rep.getAttribute("id").takeIf { it.isNotBlank() }
                        ?: "rep${video.size + audio.size}"
                    val bandwidth = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val mime = rep.getAttribute("mimeType").takeIf { it.isNotBlank() } ?: adaptationMime
                    val repBase = resolve(
                        adaptationBase, directChild(rep, "BaseURL")?.textContent?.trim()
                    )

                    val repTemplate = directChild(rep, "SegmentTemplate") ?: template
                    val repList = directChild(rep, "SegmentList") ?: segmentList
                    val repBaseSeg = directChild(rep, "SegmentBase") ?: segmentBase

                    val built = try {
                        when {
                            repTemplate != null ->
                                fromTemplate(repId, bandwidth, repBase, repTemplate, periodDuration)

                            repList != null -> fromList(repBase, repList)

                            else -> fromSingleFile(repBase, repBaseSeg)
                        }
                    } catch (t: Throwable) {
                        continue
                    }

                    val representation = Representation(
                        id = repId,
                        bandwidth = bandwidth,
                        width = rep.getAttribute("width").toIntOrNull() ?: 0,
                        height = rep.getAttribute("height").toIntOrNull() ?: 0,
                        mimeType = mime,
                        codecs = rep.getAttribute("codecs").takeIf { it.isNotBlank() },
                        init = built.first,
                        segments = built.second
                    )
                    if (representation.segments.isEmpty()) continue

                    val kind = contentType ?: when {
                        mime?.startsWith("video/") == true -> "video"
                        mime?.startsWith("audio/") == true -> "audio"
                        representation.width > 0 -> "video"
                        else -> "audio"
                    }
                    if (kind == "video") video += representation else if (kind == "audio") audio += representation
                }
            }
        }

        return Manifest(video, audio)
    }

    private fun fromTemplate(
        repId: String,
        bandwidth: Long,
        base: String,
        template: Element,
        periodDuration: Double
    ): Pair<Segment?, List<Segment>> {
        val timescale = template.getAttribute("timescale").toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val startNumber = template.getAttribute("startNumber").toLongOrNull() ?: 1L
        val mediaTemplate = template.getAttribute("media").takeIf { it.isNotBlank() }
            ?: error("SegmentTemplate 缺少 media")
        val initTemplate = template.getAttribute("initialization").takeIf { it.isNotBlank() }

        val init = initTemplate?.let {
            Segment(resolve(base, fill(it, repId, bandwidth, startNumber, 0)))
        }

        val segments = mutableListOf<Segment>()
        val timeline = directChild(template, "SegmentTimeline")

        if (timeline != null) {
            var time = 0L
            var number = startNumber
            for (s in childElements(timeline, "S")) {
                val t = s.getAttribute("t").toLongOrNull() ?: time
                val d = s.getAttribute("d").toLongOrNull() ?: 0L
                val r = s.getAttribute("r").toLongOrNull() ?: 0L
                if (d <= 0) continue

                time = t
                var repeat = r
                if (repeat < 0) {
                    repeat = if (periodDuration > 0) {
                        (((periodDuration * timescale - t) / d).toLong() - 1L).coerceAtLeast(0L)
                    } else 0L
                }
                repeat = repeat.coerceAtMost(10000L)

                for (i in 0..repeat) {
                    segments += Segment(resolve(base, fill(mediaTemplate, repId, bandwidth, number, time)))
                    number++
                    time += d
                }
            }
        } else {
            val segDuration = template.getAttribute("duration").toLongOrNull() ?: 0L
            if (segDuration > 0 && periodDuration > 0) {
                val count = kotlin.math.ceil(periodDuration * timescale / segDuration).toLong()
                    .coerceIn(1, 20000)
                for (i in 0 until count) {
                    segments += Segment(
                        resolve(
                            base,
                            fill(mediaTemplate, repId, bandwidth, startNumber + i, i * segDuration)
                        )
                    )
                }
            }
        }

        return init to segments
    }

    private fun fromList(base: String, list: Element): Pair<Segment?, List<Segment>> {
        val initUrl = directChild(list, "Initialization")
            ?.getAttribute("sourceURL")
            ?.takeIf { it.isNotBlank() }
        val init = initUrl?.let { Segment(resolve(base, it)) }

        val segments = childElements(list, "SegmentURL").mapNotNull { el ->
            val media = el.getAttribute("media").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Segment(
                url = resolve(base, media),
                range = el.getAttribute("mediaRange").takeIf { it.isNotBlank() }
            )
        }
        return init to segments
    }

    private fun fromSingleFile(base: String, segmentBase: Element?): Pair<Segment?, List<Segment>> {
        // 没有 BaseURL 时 base 会退化成 MPD 自身地址，别把清单当视频下
        if (base.isBlank() || base.substringBefore('?').endsWith(".mpd", ignoreCase = true)) {
            return null to emptyList()
        }
        val initUrl = segmentBase
            ?.let { directChild(it, "Initialization") }
            ?.getAttribute("sourceURL")
            ?.takeIf { it.isNotBlank() }
        val init = initUrl?.let { Segment(resolve(base, it)) }
        return init to listOf(Segment(base))
    }

    private fun fill(
        template: String,
        repId: String,
        bandwidth: Long,
        number: Long,
        time: Long
    ): String {
        var s = template
        s = s.replace("\$RepresentationID\$", repId)
        s = s.replace("\$Bandwidth\$", bandwidth.toString())
        s = NUMBER_RE.replace(s) { match ->
            val pad = match.groupValues[1].toIntOrNull()
            if (pad != null) number.toString().padStart(pad, '0') else number.toString()
        }
        s = TIME_RE.replace(s) { match ->
            val pad = match.groupValues[1].toIntOrNull()
            if (pad != null) time.toString().padStart(pad, '0') else time.toString()
        }
        return s
    }

    private val NUMBER_RE = Regex("\\\$Number(?:%0(\\d+)d)?\\\$")
    private val TIME_RE = Regex("\\\$Time(?:%0(\\d+)d)?\\\$")

    private fun resolve(base: String, ref: String?): String {
        if (ref.isNullOrBlank()) return base
        return runCatching { java.net.URL(java.net.URL(base), ref).toString() }.getOrDefault(ref)
    }

    private fun directChild(parent: Element, name: String): Element? =
        childElements(parent, name).firstOrNull()

    private fun childElements(parent: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == name) {
                out += node
            }
        }
        return out
    }

    /** PT1H2M3.5S / P1DT2H */
    private fun parseIsoDuration(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val m = DURATION_RE.find(raw) ?: return 0.0
        val day = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val hour = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val minute = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val second = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return day * 86400 + hour * 3600 + minute * 60 + second
    }

    private val DURATION_RE =
        Regex("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?")
}
