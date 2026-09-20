package com.yacin

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URLEncoder

class YacineTvProvider : MainAPI() {
    override var mainUrl = "https://def.ycnapi.com/api"
    private val fallbackUrl = "https://deft.yacinelive.com/api"

    override var name = "YacineTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val baseKey = "c!xZj+N9&G@Ev@vw"

    data class LinkData(
        @JsonProperty("kind") val kind: String = "event",
        @JsonProperty("eventId") val eventId: String? = null,
        @JsonProperty("name") val name: String = "",
        @JsonProperty("channel") val channel: String? = null,
    )

    data class YacineEventResponse(
        @JsonProperty("data") val data: List<YacineEvent>? = null,
    )

    data class YacineEvent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("channel") val channel: String? = null,
        @JsonProperty("start_time") val startTime: Long? = null,
        @JsonProperty("end_time") val endTime: Long? = null,
        @JsonProperty("team_1") val team1: YacineTeam? = null,
        @JsonProperty("team_2") val team2: YacineTeam? = null,
    )

    data class YacineTeam(
        @JsonProperty("name") val name: String? = null,
    )

    data class YacineCategoryEnvelope(
        @JsonProperty("data") val data: List<YacineCategory>? = null,
    )

    data class YacineCategory(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("name") val name: String? = null,
    )

    data class YacineChannelResponse(
        @JsonProperty("data") val data: List<YacineChannel>? = null,
    )

    data class YacineChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class YacineStreamResponse(
        @JsonProperty("data") val data: List<YacineStream>? = null,
    )

    data class YacineStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("referer") val referer: String? = null,
        @JsonProperty("user_agent") val userAgent: String? = null,
        @JsonProperty("url_type") val urlType: Int? = null,
        @JsonProperty("headers") val headers: Map<String, Any?>? = null,
    )

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            val fullKey = baseKey + tHeader
            val decoded = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val out = ByteArray(decoded.size)
            for (i in decoded.indices) {
                out[i] = (decoded[i].toInt() xor fullKey[i % fullKey.length].code).toByte()
            }
            String(out)
        } catch (_: Exception) { "" }
    }

    private fun join(base: String, path: String): String {
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }

    private suspend inline fun <T> retryIO(
        times: Int = 3,
        delayMs: Long = 800,
        block: suspend () -> T?,
    ): T? {
        repeat(times) { attempt ->
            runCatching { block() }.getOrNull()?.let { return it }
            if (attempt < times - 1) runCatching { delay(delayMs) }
        }
        return null
    }

    private suspend fun getDecrypted(path: String): String? {
        return retryIO(times = 5) {
            for (base in listOf(mainUrl, fallbackUrl)) {
                try {
                    val res = app.get(
                        join(base, path),
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        timeout = 15,
                    )
                    if (res.code == 200 && res.text.isNotBlank()) {
                        return@retryIO decrypt(res.text, res.headers["t"] ?: "")
                    }
                } catch (_: Exception) { continue }
            }
            null
        }
    }

    private suspend fun getEvents(): List<YacineEvent> {
        val json = getDecrypted("events") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineEventResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getCategories(): List<YacineCategory> {
        val json = getDecrypted("categories") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineCategoryEnvelope>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getChannels(categoryId: String): List<YacineChannel> {
        val json = getDecrypted("categories/$categoryId/channels") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineChannelResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getChannelStreams(channelId: String): List<YacineStream> {
        val json = getDecrypted("channel/$channelId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEventStreams(eventId: String): List<YacineStream> {
        val json = getDecrypted("event/$eventId")
            ?: getDecrypted("event/$eventId/servers")
            ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private val beinQualityRegex =
        Regex("""be\s*in\s*sports\s*\(?\s*(\d+\s*p)\s*\)?""", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), " ")

    /** Card channel label -> live channel ids (every quality group). The
     * label on the card is what must play; event servers are fallback. */
    private suspend fun resolveChannelIds(label: String): List<String> {
        val want = norm(label)
        if (want.isBlank()) return emptyList()
        runCatching {
            val json = getDecrypted("search?query=${URLEncoder.encode(label.trim(), "UTF-8")}")
                ?: return@runCatching null
            if (json.isBlank()) return@runCatching null
            parseJson<YacineChannelResponse>(json).data.orEmpty()
                .filter { norm(it.name ?: "") == want }
                .mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }
                .distinct()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            coroutineScope {
                getCategories().filter { beinQualityRegex.containsMatchIn(it.name ?: "") }
                    .map { cat -> async { getChannels(cat.id) } }
                    .awaitAll().flatten()
                    .filter { norm(it.name ?: "") == want }
                    .mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }
                    .distinct()
            }
        }.getOrNull() ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.isNotBlank() || page > 1) {
            return newHomePageResponse(emptyList(), false)
        }
        val nowSec = System.currentTimeMillis() / 1000
        val items = getEvents()
            .filter { e -> !e.id.isNullOrBlank() }
            .sortedWith(
                compareBy(
                    { e ->
                        when {
                            e.startTime != null && e.startTime > 0 && nowSec < e.startTime -> 1 // upcoming
                            e.endTime != null && e.endTime > 0 && nowSec > e.endTime -> 2 // ended
                            else -> 0 // live
                        }
                    },
                    { e -> e.startTime ?: Long.MAX_VALUE },
                )
            )
            .map { e ->
                val t1 = e.team1?.name?.trim().orEmpty()
                val t2 = e.team2?.name?.trim().orEmpty()
                val title = if (t1.isNotBlank() && t2.isNotBlank()) "$t1 × $t2" else "مباراة"
                val data = LinkData(
                    eventId = e.id,
                    name = title,
                    channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                ).toJson()
                newLiveSearchResponse(title, data, TvType.Live)
            }
        return newHomePageResponse(listOf(HomePageList("Today's Matches", items, isHorizontalImages = true)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        return newMovieLoadResponse(data.name, url, TvType.Live, url)
    }

    private fun qualityFor(label: String, url: String): Int {
        val q = getQualityFromName("$label $url")
        if (q != Qualities.Unknown.value) return q
        val u = url.lowercase()
        return when {
            "1080" in label || "1080" in u -> Qualities.P1080.value
            "720" in label || "720" in u -> Qualities.P720.value
            "480" in label || "480" in u -> Qualities.P480.value
            "360" in label || "360" in u -> Qualities.P360.value
            label.equals("HD", ignoreCase = true) -> Qualities.P1080.value
            label.equals("SD", ignoreCase = true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    private fun streamHeaders(s: YacineStream): Map<String, String> {
        val out = mutableMapOf<String, String>()
        s.headers?.forEach { (k, v) ->
            val vs = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> null
            }
            if (!vs.isNullOrBlank()) out[k] = vs
        }
        out["User-Agent"] = s.userAgent?.takeIf { it.isNotBlank() }
            ?: out["User-Agent"]?.takeIf { it.isNotBlank() }
            ?: "okhttp/4.12.0"
        (s.referer?.takeIf { it.isNotBlank() } ?: out["Referer"])
            ?.takeIf { it.isNotBlank() }?.let { out["Referer"] = it }
        return out
    }

    /** Tokenized masters (?t=&e=) resolve to the best variant up-front:
     * players drop the query on relative refs, so the raw master URL
     * alone yields dead subrequests. */
    private suspend fun emitBestVariant(
        channelName: String,
        serverName: String,
        masterUrl: String,
        headers: Map<String, String>,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val qIndex = masterUrl.indexOf('?')
            if (qIndex < 0) return false
            val query = masterUrl.substring(qIndex)
            val base = masterUrl.substring(0, qIndex)
            val master = app.get(masterUrl, headers = headers, timeout = 8).text
            if ("#EXTM3U" !in master || "#EXT-X-STREAM-INF" !in master) return false
            var bestUri: String? = null
            var bestBw = -1
            val lines = master.lines()
            for (i in lines.indices) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(lines[i])
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                val uri = lines.getOrNull(i + 1)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                if (bw > bestBw) {
                    bestBw = bw
                    bestUri = uri
                }
            }
            val rel = bestUri ?: return false
            val variantUrl = if ('?' in rel) {
                if (rel.startsWith("http")) rel else join(base.substringBeforeLast("/"), rel)
            } else {
                val abs = if (rel.startsWith("http")) rel.substringBefore('?')
                else join(base.substringBeforeLast("/"), rel)
                abs + query
            }
            val check = app.get(variantUrl, headers = headers, timeout = 8)
            if (check.code != 200 || "#EXTM3U" !in check.text) return false
            callback.invoke(
                newExtractorLink(this.name, "$channelName • $serverName", variantUrl) {
                    this.headers = headers
                    this.referer = referer
                    this.quality = qualityFor("$channelName $serverName", variantUrl)
                    this.type = ExtractorLinkType.M3U8
                }
            )
            true
        } catch (_: Exception) { false }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val info = parseJson<LinkData>(data)
        var found = false
        val seenUrls = mutableSetOf<String>()

        suspend fun emit(channelName: String, s: YacineStream): Boolean {
            val raw = s.url?.trim()?.takeIf { it.isNotBlank() } ?: return false
            if (!seenUrls.add(raw)) return false
            val headers = streamHeaders(s)
            val referer = headers["Referer"]?.takeIf { it.isNotBlank() }
                ?: s.referer?.takeIf { it.isNotBlank() }
                ?: raw
            val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val lower = raw.lowercase()
            val isDirect = ".m3u8" in lower || s.urlType == 1 || s.urlType == 3 ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
            if (!isDirect) {
                var resolved = false
                runCatching {
                    loadExtractor(raw, referer, subtitleCallback) { resolved = true; callback(it) }
                }
                if (resolved) found = true
                return resolved
            }
            if (".m3u8" in lower && "?" in raw) {
                if (emitBestVariant(channelName, serverName, raw, headers, referer, callback)) {
                    found = true
                    return true
                }
            }
            callback.invoke(
                newExtractorLink(this.name, "$channelName • $serverName", raw) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: ""
                    this.quality = qualityFor("$channelName $serverName", raw)
                    this.type = if (".m3u8" in raw) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
            return true
        }

        // The card's channel label is what must play (every quality).
        val tag = info.channel?.trim()?.takeIf { it.isNotEmpty() }
        if (tag != null) {
            val ids = resolveChannelIds(tag)
            if (ids.isNotEmpty()) {
                coroutineScope {
                    ids.map { cid -> async { getChannelStreams(cid) } }
                        .awaitAll().flatten()
                        .forEach { if (emit(tag, it)) found = true }
                }
                if (found) return true
            }
        }
        // Fallback: event servers.
        val eid = info.eventId?.takeIf { it.isNotBlank() } ?: return found
        getEventStreams(eid).forEach { if (emit(tag ?: info.name, it)) found = true }
        return found
    }
}
