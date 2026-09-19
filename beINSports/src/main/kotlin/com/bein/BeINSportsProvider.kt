package com.bein

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
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
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** beIN Sports guide for Morocco: fixtures, official names, leagues and
 * live state from FotMob; playback resolves the airing beIN channel
 * through the Yacine TV API event list and plays its streams.
 * Non-beIN fixtures (STARZPLAY/Shahid/Thmanyah-only) are hidden. */
class BeINSportsProvider : MainAPI() {
    override var mainUrl = "https://www.fotmob.com"
    override var name = "beINSports"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    private val yacineBases = listOf("https://def.ycnapi.com/api", "https://deft.yacinelive.com/api")
    private val baseKey = "c!xZj+N9&G@Ev@vw"
    private val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    // ---- FotMob models (partial; unknown fields ignored) ----

    data class FmMatches(
        @JsonProperty("leagues") val leagues: List<FmLeague>? = null,
    )

    data class FmLeague(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("matches") val matches: List<FmMatch>? = null,
    )

    data class FmTeam(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("shortName") val shortName: String? = null,
        @JsonProperty("score") val score: Int? = null,
    )

    data class FmLiveTime(
        @JsonProperty("short") val short: String? = null,
    )

    data class FmStatus(
        @JsonProperty("started") val started: Boolean? = null,
        @JsonProperty("finished") val finished: Boolean? = null,
        @JsonProperty("ongoing") val ongoing: Boolean? = null,
        @JsonProperty("cancelled") val cancelled: Boolean? = null,
        @JsonProperty("liveTime") val liveTime: FmLiveTime? = null,
    )

    data class FmMatch(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("home") val home: FmTeam? = null,
        @JsonProperty("away") val away: FmTeam? = null,
        @JsonProperty("status") val status: FmStatus? = null,
        @JsonProperty("timeTS") val timeTS: Long? = null,
    )

    data class TvStationInfo(
        @JsonProperty("name") val name: String? = null,
    )

    data class TvListing(
        @JsonProperty("station") val station: TvStationInfo? = null,
    )

    private data class Fixture(
        val league: String?,
        val match: FmMatch,
        val beinChannels: List<String>,
    )

    data class LinkData(
        @JsonProperty("fmid") val fmid: Long? = null,
        @JsonProperty("name") val name: String = "",
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("league") val league: String? = null,
        @JsonProperty("kickoffMs") val kickoffMs: Long? = null,
        @JsonProperty("channels") val channels: List<String> = emptyList(),
        @JsonProperty("plot") val plot: String? = null,
    )

    // ---- Yacine models (partial; stream backend only) ----

    data class YacineEventResponse(
        @JsonProperty("data") val data: List<YacineEvent>? = null,
    )

    data class YacineEvent(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("channel") val channel: String? = null,
        @JsonProperty("start_time") val startTime: Long? = null,
        @JsonProperty("end_time") val endTime: Long? = null,
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

    // ---- shared helpers ----

    private fun join(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = path.trimStart('/')
        return "$b/$p"
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

    private fun teamLogo(teamId: Long?): String? {
        if (teamId == null) return null
        return "https://images.fotmob.com/image_resources/logo/teamlogo/$teamId.png"
    }

    /** Device-local day as FotMob wants it (YYYYMMDD). */
    private fun fotmobDay(offsetDays: Int): String {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        }
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
    }

    /** Kickoff label, device-local: "Today 19:45", "Tomorrow 20:00",
     * "22 Sep, 18:45". Empty when already started. */
    private fun formatKickoff(timeMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (timeMs == null || timeMs <= 0 || timeMs <= nowMs) return ""
        return try {
            val tz = TimeZone.getDefault()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
            val nowDate = dateFmt.format(Date(nowMs))
            val kickDate = dateFmt.format(Date(timeMs))
            val time = timeFmt.format(Date(timeMs))
            if (kickDate == nowDate) {
                "Today $time"
            } else {
                val cal = java.util.Calendar.getInstance(tz).apply {
                    timeInMillis = nowMs
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                if (kickDate == dateFmt.format(cal.time)) {
                    "Tomorrow $time"
                } else {
                    SimpleDateFormat("dd MMM, HH:mm", Locale.US).apply { timeZone = tz }
                        .format(Date(timeMs))
                }
            }
        } catch (_: Exception) { "" }
    }

    /** Live minute cleaned of RTL marks ("22'"). Null when absent. */
    private fun liveMinute(m: FmMatch): String? {
        return m.status?.liveTime?.short
            ?.filter { it.isLetterOrDigit() || it == '\'' || it == '+' }
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.any { c -> c.isDigit() } && !it.endsWith("'")) "$it'" else it }
    }

    private fun isLive(m: FmMatch): Boolean {
        val st = m.status ?: return false
        if (st.cancelled == true || st.finished == true) return false
        return st.ongoing == true || st.started == true
    }

    private fun isEnded(m: FmMatch): Boolean {
        return m.status?.finished == true
    }

    private fun fixtureTitle(f: Fixture): String {
        val home = f.match.home?.name?.trim().orEmpty()
        val away = f.match.away?.name?.trim().orEmpty()
        val matchup = if (home.isNotBlank() && away.isNotBlank()) "$home vs $away" else "Match"
        val hs = f.match.home?.score
        val aws = f.match.away?.score
        return when {
            isEnded(f.match) && hs != null && aws != null -> "✅ FT $home $hs-$aws $away"
            isEnded(f.match) -> "✅ $matchup"
            isLive(f.match) && hs != null && aws != null ->
                liveMinute(f.match)?.let { "🔴 $it $home $hs-$aws $away" } ?: "🔴 $matchup"
            isLive(f.match) ->
                liveMinute(f.match)?.let { "🔴 $it $matchup" } ?: "🔴 $matchup"
            else -> "🔜 $matchup"
        }
    }

    private fun fixtureRank(f: Fixture): Int = when {
        isLive(f.match) -> 0
        isEnded(f.match) -> 2
        else -> 1
    }

    /** Channel compare across providers: case-insensitive, HD dropped
     * ("beIN Sports HD 1" vs "beIN SPORTS 1"). */
    private fun normChannel(s: String): String {
        return s.lowercase()
            .replace(Regex("""\bhd\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    // ---- FotMob guide backend ----

    private suspend fun getDayPool(day: String): List<FmLeague> {
        val txt = retryIO(times = 2) {
            runCatching {
                app.get(
                    "https://www.fotmob.com/api/data/matches?date=$day",
                    headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                    timeout = 10,
                ).text.takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: return emptyList()
        return runCatching { parseJson<FmMatches>(txt).leagues.orEmpty() }.getOrNull() ?: emptyList()
    }

    /** Morocco listings by FotMob match id. One request per build. */
    private suspend fun getListings(): Map<String, List<TvListing>> {
        val txt = retryIO(times = 2) {
            runCatching {
                app.get(
                    "https://www.fotmob.com/api/data/tvlistings?countryCode=MA",
                    headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                    timeout = 10,
                ).text.takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: return emptyMap()
        return runCatching {
            parseJson<Map<String, List<TvListing>>>(txt)
        }.getOrNull() ?: emptyMap()
    }

    /** Fixtures airing on beIN in Morocco. Listings missing (fetch flap)
     * degrades to unfiltered so the guide still renders. */
    private fun beinFixtures(
        leagues: List<FmLeague>,
        listings: Map<String, List<TvListing>>?,
    ): List<Fixture> {
        val out = mutableListOf<Fixture>()
        leagues.forEach { l ->
            l.matches.orEmpty().forEach { m ->
                val id = m.id ?: return@forEach
                if (m.status?.cancelled == true) return@forEach
                if (m.timeTS == null) return@forEach
                val stations = listings
                    ?.get(id.toString()).orEmpty()
                    .mapNotNull { it.station?.name?.trim()?.takeIf { n -> n.isNotBlank() } }
                    .distinct()
                val bein = stations.filter { it.contains("bein", ignoreCase = true) }
                // Without listings we cannot filter: show everything, no channels line.
                if (listings != null && bein.isEmpty()) return@forEach
                out.add(Fixture(league = l.name, match = m, beinChannels = bein))
            }
        }
        return out.sortedWith(compareBy({ fixtureRank(it) }, { it.match.timeTS }))
    }

    private fun fixtureLink(f: Fixture): LinkData {
        val m = f.match
        val poster = teamLogo(m.home?.id) ?: teamLogo(m.away?.id)
        return LinkData(
            fmid = m.id,
            name = fixtureTitle(f),
            poster = poster,
            league = f.league,
            kickoffMs = m.timeTS,
            channels = f.beinChannels,
            plot = fixtureTitle(f),
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.isNotBlank()) {
            val rows = buildHomeLists()
            val match = rows.find { it.name == request.name }
                ?: return newHomePageResponse(request, emptyList(), false)
            return newHomePageResponse(request, match.list, false)
        }
        if (page > 1) return newHomePageResponse(emptyList(), false)
        return newHomePageResponse(buildHomeLists(), false)
    }

    private suspend fun buildHomeLists(): List<HomePageList> {
        return coroutineScope {
            val todayDeferred = async { withTimeoutOrNull(15_000) { getDayPool(fotmobDay(0)) } ?: emptyList() }
            val tomorrowDeferred = async { withTimeoutOrNull(15_000) { getDayPool(fotmobDay(1)) } ?: emptyList() }
            val listingsDeferred = async { withTimeoutOrNull(15_000) { getListings() } }
            val listings = listingsDeferred.await()
            val rows = listOf("Today's Matches" to todayDeferred, "Tomorrow's Matches" to tomorrowDeferred)
                .mapNotNull { (title, deferred) ->
                    val links = beinFixtures(deferred.await(), listings).map { fixtureLink(it) }
                    if (links.isEmpty()) return@mapNotNull null
                    val items = links.map { link ->
                        newLiveSearchResponse(link.name, link.toJson(), TvType.Live) {
                            this.posterUrl = link.poster
                        }
                    }
                    HomePageList(title, items, isHorizontalImages = true)
                }
            rows
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        return coroutineScope {
            val todayDeferred = async { withTimeoutOrNull(12_000) { getDayPool(fotmobDay(0)) } ?: emptyList() }
            val tomorrowDeferred = async { withTimeoutOrNull(12_000) { getDayPool(fotmobDay(1)) } ?: emptyList() }
            val listingsDeferred = async { withTimeoutOrNull(12_000) { getListings() } }
            val listings = listingsDeferred.await()
            val out = mutableListOf<SearchResponse>()
            listOf(todayDeferred.await(), tomorrowDeferred.await()).forEach { leagues ->
                beinFixtures(leagues, listings)
                    .filter { f ->
                        val hay = listOfNotNull(
                            f.match.home?.name, f.match.away?.name, f.league,
                        ).joinToString(" ")
                        hay.contains(q, ignoreCase = true)
                    }.take(30 - out.size)
                    .forEach { f ->
                        val link = fixtureLink(f)
                        out.add(
                            newLiveSearchResponse(link.name, link.toJson(), TvType.Live) {
                                this.posterUrl = link.poster
                            }
                        )
                    }
            }
            out
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        // Fresh title/state on open: re-pair by FotMob match id.
        var name = data.name
        var league = data.league
        val fresh = withTimeoutOrNull(8_000) {
            val pools = listOf(fotmobDay(0), fotmobDay(1)).map { getDayPool(it) }
            pools.flatMap { leagues ->
                leagues.flatMap { l -> l.matches.orEmpty().map { m -> Fixture(l.name, m, emptyList()) } }
            }.firstOrNull { it.match.id != null && it.match.id == data.fmid }
        }
        fresh?.let {
            name = fixtureTitle(it)
            league = it.league ?: league
        }
        val banner = data.poster
        val plotLines = listOfNotNull(
            formatKickoff(data.kickoffMs).takeIf { it.isNotBlank() }?.let { "🕐 $it" },
            league?.takeIf { it.isNotBlank() }?.let { "🏆 $it" },
            data.channels.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "📺 $it" },
        )
        val plot = plotLines.takeIf { it.isNotEmpty() }?.joinToString("<br><br>")
            ?: data.plot
            ?: data.name
        return newMovieLoadResponse(name, url, TvType.Live, url) {
            this.posterUrl = banner
            this.backgroundPosterUrl = banner
            this.plot = plot
        }
    }

    // ---- Yacine stream backend ----

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            val fullKey = baseKey + tHeader
            val decoded = android.util.Base64.decode(encryptedText.trim(), android.util.Base64.DEFAULT)
            val out = ByteArray(decoded.size)
            for (i in decoded.indices) {
                out[i] = (decoded[i].toInt() xor fullKey[i % fullKey.length].code).toByte()
            }
            String(out)
        } catch (_: Exception) { "" }
    }

    private suspend fun getDecrypted(path: String): Pair<String, String>? {
        return retryIO(times = 4) {
            for (base in yacineBases) {
                try {
                    val res = app.get(
                        join(base, path),
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        timeout = 12,
                    )
                    if (res.code == 200 && res.text.isNotBlank()) {
                        val t = res.headers["t"] ?: ""
                        return@retryIO decrypt(res.text, t) to t
                    }
                } catch (_: Exception) { continue }
            }
            null
        }
    }

    private suspend fun getEvents(): List<YacineEvent> {
        val (json) = getDecrypted("events") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineEventResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEventStreams(eventId: Long): List<YacineStream> {
        val (json) = getDecrypted("event/$eventId") ?: getDecrypted("event/$eventId/servers") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
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
        val ua = s.userAgent?.takeIf { it.isNotBlank() }
            ?: out["User-Agent"]?.takeIf { it.isNotBlank() }
            ?: BROWSER_UA
        out["User-Agent"] = ua
        val ref = s.referer?.takeIf { it.isNotBlank() } ?: out["Referer"]
        if (!ref.isNullOrBlank()) out["Referer"] = ref
        return out
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

    private suspend fun emitBestVariant(
        channelName: String,
        serverName: String,
        masterUrl: String,
        headers: Map<String, String>,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        return try {
            val qIndex = masterUrl.indexOf('?')
            if (qIndex < 0) return 0
            val query = masterUrl.substring(qIndex)
            val base = masterUrl.substring(0, qIndex)
            val master = app.get(masterUrl, headers = headers, timeout = 8).text
            if ("#EXTM3U" !in master) return 0
            if ("#EXT-X-STREAM-INF" !in master) return 0
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
            val rel = bestUri ?: return -1
            val variantUrl = if ('?' in rel) {
                if (rel.startsWith("http")) rel else join(base.substringBeforeLast("/"), rel)
            } else {
                val abs = if (rel.startsWith("http")) rel.substringBefore('?')
                else join(base.substringBeforeLast("/"), rel)
                abs + query
            }
            val check = app.get(variantUrl, headers = headers, timeout = 8)
            if (check.code != 200 || "#EXTM3U" !in check.text) return -1
            callback.invoke(
                newExtractorLink(this.name, "$channelName • $serverName", variantUrl) {
                    this.headers = headers
                    this.referer = referer
                    this.quality = qualityFor("$channelName $serverName", variantUrl)
                    this.type = ExtractorLinkType.M3U8
                }
            )
            1
        } catch (_: Exception) { 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val info = parseJson<LinkData>(data)
        // Pair the FotMob fixture to a Yacine event: kickoff within
        // 10 minutes and a normalized beIN channel on both sides.
        val kickoff = info.kickoffMs ?: return false
        val wanted = info.channels.map { normChannel(it) }.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return false
        val events = withTimeoutOrNull(20_000) { getEvents() } ?: return false
        val yc = events.mapNotNull { ev ->
            val st = ev.startTime?.times(1000) ?: return@mapNotNull null
            val dt = abs(st - kickoff)
            if (dt > 10 * 60 * 1000) return@mapNotNull null
            val ec = ev.channel?.let { normChannel(it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (wanted.none { it == ec }) return@mapNotNull null
            ev to dt
        }.minByOrNull { it.second }?.first ?: return false
        val tag = yc.channel?.trim()?.takeIf { it.isNotEmpty() } ?: info.name
        val streams = getEventStreams(yc.id ?: return false)
        var found = false
        val seenUrls = mutableSetOf<String>()
        streams.forEach { s ->
            val raw = s.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!seenUrls.add(raw)) return@forEach
            val headers = streamHeaders(s)
            val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val label = if (!serverName.equals(tag, ignoreCase = true)) "$tag • $serverName" else serverName
            val lower = raw.lowercase()
            val isDirect = ".m3u8" in lower || s.urlType == 1 || s.urlType == 3 ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
            if (!isDirect) {
                val eventReferer = headers["Referer"]?.takeIf { it.isNotBlank() }
                    ?: s.referer?.takeIf { it.isNotBlank() }
                    ?: raw
                var resolved = false
                val countCb: (ExtractorLink) -> Unit = { resolved = true; callback(it) }
                runCatching { loadExtractor(raw, eventReferer, subtitleCallback, countCb) }
                if (resolved) found = true
                return@forEach
            }
            if (".m3u8" in lower && "?" in raw) {
                val eventRef = headers["Referer"]?.takeIf { it.isNotBlank() }
                    ?: s.referer?.takeIf { it.isNotBlank() }
                    ?: raw
                when (emitBestVariant(tag, serverName, raw, headers, eventRef, callback)) {
                    1 -> {
                        found = true
                        return@forEach
                    }
                    -1 -> return@forEach
                }
            }
            callback.invoke(
                newExtractorLink(this.name, label, raw) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: ""
                    this.quality = qualityFor(label, raw)
                    this.type = if (".m3u8" in lower) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
        }
        return found
    }
}
