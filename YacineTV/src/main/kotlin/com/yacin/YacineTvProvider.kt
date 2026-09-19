package com.yacin

import android.content.Context
import android.util.Base64
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** YacineTV: match guide from the official beIN Sports EPG (ar-mena),
 * playback from the Yacine TV API event streams.
 * A row appears only when an EPG fixture pairs with a Yacine event
 * (kickoff within 10 min + same normalized channel), so every card
 * is playable. No art or live-state upstreams: neutral placeholder
 * art, status from Yacine clocks. */
class YacineTvProvider : MainAPI() {
    override var mainUrl = "https://def.ycnapi.com/api"
    private val fallbackUrl = "https://deft.yacinelive.com/api"

    override var name = "YacineTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val baseKey = "c!xZj+N9&G@Ev@vw"
    private val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private var appContext: Context? = null

    /** Called by the plugin entry point — MainAPI has no context hook. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class LinkData(
        @JsonProperty("kind") val kind: String = "event",
        @JsonProperty("id") val id: Long? = null, // Yacine event id (stream source)
        @JsonProperty("name") val name: String = "",
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("league") val league: String? = null, // EPG competition
        @JsonProperty("channel") val channel: String? = null, // official beIN channel
        @JsonProperty("commentary") val commentary: String? = null,
        @JsonProperty("kickoff") val kickoff: String? = null, // baked, refreshed at load
        @JsonProperty("startTime") val startTime: Long? = null, // epoch sec (Yacine)
        @JsonProperty("endTime") val endTime: Long? = null, // epoch sec (Yacine)
        @JsonProperty("desc") val desc: String? = null, // EPG Opta preview text
        @JsonProperty("related") val related: List<LinkData>? = null,
        @JsonProperty("plot") val plot: String? = null,
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
        val b = base.trimEnd('/')
        val p = path.trimStart('/')
        return "$b/$p"
    }

    /** Cricify parity: every data request opts out of HTTP caching. */
    private val noCacheHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Cache-Control" to "no-cache, no-store",
    )

    /** Retries a nullable suspend block (first non-null wins). */
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

    /** Remote API-base config: primary + fallbacks fetched from GitHub at
     * runtime so hosts can move without an app update. Hardcoded pair is
     * the default when remote is unreachable or malformed. */
    private val apiBasesUrl =
        "https://raw.githubusercontent.com/clearpath-mind/cs-extensions/main/YacineTV/api_bases.json"

    data class ApiBases(
        @JsonProperty("primary") val primary: String? = null,
        @JsonProperty("fallbacks") val fallbacks: List<String>? = null,
    )

    @Volatile
    private var resolvedBases: List<String>? = null

    private fun sanitizeBase(u: String?): String? {
        val s = u?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null
        return s
    }

    private suspend fun resolveBases(): List<String> {
        resolvedBases?.takeIf { it.isNotEmpty() }?.let { return it }
        val defaults = listOf(mainUrl, fallbackUrl)
        val fresh = retryIO(times = 2) {
            runCatching {
                app.get(apiBasesUrl, headers = noCacheHeaders, timeout = 8).text
                    .takeIf { it.isNotBlank() }?.let { txt ->
                        val b = parseJson<ApiBases>(txt)
                        listOfNotNull(sanitizeBase(b.primary)) +
                            b.fallbacks.orEmpty().mapNotNull { sanitizeBase(it) }
                    }?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        val resolved = fresh
            ?: prefs()?.getString("api_bases", null)
                ?.split("|")?.mapNotNull { sanitizeBase(it) }
                ?.takeIf { it.isNotEmpty() }
            ?: defaults
        resolvedBases = resolved
        if (fresh != null) {
            runCatching { prefs()?.edit()?.putString("api_bases", fresh.joinToString("|"))?.apply() }
        }
        return resolved
    }

    private fun prefs() =
        runCatching { appContext?.getSharedPreferences("yacinetv", Context.MODE_PRIVATE) }.getOrNull()

    private fun prefsKey(path: String) = "payload_" + path.replace(Regex("[^A-Za-z0-9]"), "_")

    // Raw Yacine models (slim: guide pairing + playback only).
    data class YacineEventResponse(
        @JsonProperty("data") val data: List<YacineEvent>? = null,
    )

    data class YacineEvent(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("channel") val channel: String? = null,
        @JsonProperty("commentary") val commentary: String? = null,
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

    private suspend fun getDecrypted(path: String): Pair<String, String>? {
        // The API flaps (connection resets on both bases); retry across
        // all resolved bases. Attempts are cheap when connections reset
        // fast; the 15s timeout covers slow-but-alive responses.
        return retryIO(times = 5) {
            for (base in resolveBases()) {
                try {
                    val res = app.get(
                        join(base, path),
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        timeout = 15,
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

    /** Last-good decrypted payloads per path (stale-while-revalidate).
     * Both bases reset connections at times; when a fresh fetch fails,
     * getters fall back to these instead of rendering empty rows.
     * Memory-first, SharedPreferences-backed so stale data survives
     * process death. */
    @Volatile
    private var lastPayloads: Map<String, String> = emptyMap()

    /** Fresh payload, or the last-good cached one when the API flaps.
     * Only non-blank responses are remembered, so a genuine empty
     * ({"data":[]}) still parses to empty instead of serving stale data. */
    private suspend fun getPayload(path: String): String? {
        val (json) = getDecrypted(path) ?: return stalePayload(path)
        if (json.isBlank()) return stalePayload(path)
        rememberPayload(path, json)
        return json
    }

    private fun stalePayload(path: String): String? {
        lastPayloads[path]?.let { return it }
        // Warm memory from disk so the next lookup is cheap.
        return prefs()?.getString(prefsKey(path), null)?.takeIf { it.isNotBlank() }?.also {
            lastPayloads = lastPayloads + (path to it)
        }
    }

    private fun rememberPayload(path: String, json: String) {
        lastPayloads = lastPayloads + (path to json)
        runCatching { prefs()?.edit()?.putString(prefsKey(path), json)?.apply() }
    }

    private suspend fun getEvents(): List<YacineEvent> {
        val json = getPayload("events") ?: return emptyList()
        return runCatching {
            parseJson<YacineEventResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEventStreams(eventId: Long): List<YacineStream> {
        // /event/{id} and /event/{id}/servers return the same server list.
        // Streams stay fresh-only: stale URLs die fast.
        val (json) = getDecrypted("event/$eventId") ?: getDecrypted("event/$eventId/servers") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    // ---- Official beIN EPG backend (guide authority) ----

    data class EpgChannels(
        @JsonProperty("rows") val rows: List<EpgChannel>? = null,
    )

    data class EpgChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class EpgEvents(
        @JsonProperty("rows") val rows: List<EpgEvent>? = null,
    )

    data class EpgEvent(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("startDate") val startDate: String? = null,
        @JsonProperty("endDate") val endDate: String? = null,
    )

    private data class GuideFixture(
        val home: String,
        val away: String,
        val league: String?,
        val channels: List<String>, // official beIN channel names
        val desc: String?,
        val yacine: YacineEvent, // paired stream source
    )

    private suspend fun getEpgChannels(): List<EpgChannel> {
        val txt = retryIO(times = 2) {
            runCatching {
                app.get(
                    "https://www.beinsports.com/api/opta/tv-channel?region=ar-mena",
                    headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                    timeout = 10,
                ).text.takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: return emptyList()
        return runCatching { parseJson<EpgChannels>(txt).rows.orEmpty() }.getOrNull() ?: emptyList()
    }

    private suspend fun getEpgEvents(channelId: String, startBefore: String, endAfter: String): List<EpgEvent> {
        val q = "startBefore=${URLEncoder.encode(startBefore, "UTF-8")}" +
            "&endAfter=${URLEncoder.encode(endAfter, "UTF-8")}" +
            "&channelIds=${URLEncoder.encode(channelId, "UTF-8")}"
        val txt = retryIO(times = 2) {
            runCatching {
                app.get(
                    "https://www.beinsports.com/api/opta/tv-event?$q",
                    headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                    timeout = 10,
                ).text.takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: return emptyList()
        return runCatching {
            // Envelope {rows:[...]}; fall back to a bare array just in case.
            runCatching { parseJson<EpgEvents>(txt).rows.orEmpty() }.getOrNull()
                ?: parseJson<List<EpgEvent>>(txt)
        }.getOrNull() ?: emptyList()
    }

    /** Channel compare across providers: case-insensitive, HD dropped
     * ("beIN Sports HD 1" vs "beIN SPORTS 1"). */
    private fun normChannel(s: String): String {
        return s.lowercase()
            .replace(Regex("""\bhd\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normTeam(s: String): String {
        return s.lowercase().replace(Regex("""\s+"""), " ").trim()
    }

    /** EPG title -> (home, away, league). "Brighton vs Arsenal -
     * English Premier League 2026/2027 - Week 5". Non-football titles
     * ("Team A @ Team B", channel filler) return null. */
    private fun parseEpgTitle(title: String?): Triple<String, String, String?>? {
        val parts = title?.split(" - ").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val teams = parts[0].split(" vs ").map { it.replace(Regex("""\s+"""), " ").trim() }
        if (teams.size != 2 || teams.any { it.isBlank() }) return null
        val league = parts[1].replace(Regex("""\s+\d{4}/\d{2,4}\s*$"""), "").trim().takeIf { it.isNotBlank() }
        return Triple(teams[0], teams[1], league)
    }

    private fun epgStartMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.time.parse(iso)?.time
        }.getOrNull()
    }

    private fun utcDayStart(offsetDays: Int): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        }
        return cal.timeInMillis
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    /** Guide fixtures for a 48h UTC window: EPG events on channels that
     * Yacine airs, each paired to its Yacine event (kickoff within
     * 10 min + same normalized channel). Unpaired rows are dropped:
     * every card must be playable. */
    private suspend fun guideFixtures(): List<GuideFixture> {
        return coroutineScope {
            val eventsDeferred = async { withTimeoutOrNull(20_000) { getEvents() } ?: emptyList() }
            val channelsDeferred = async { withTimeoutOrNull(15_000) { getEpgChannels() } ?: emptyList() }
            val events = eventsDeferred.await()
            if (events.isEmpty()) return@coroutineScope emptyList()
            val epgByNorm = channelsDeferred.await()
                .mapNotNull { c ->
                    val name = c.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val id = c.id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    normChannel(name) to (name to id)
                }.toMap()
            // Only query EPG channels Yacine actually airs today.
            val wanted = events.mapNotNull { it.channel?.let { ch -> normChannel(ch) } }
                .toSet().mapNotNull { epgByNorm[it] }.distinct()
            if (wanted.isEmpty()) return@coroutineScope emptyList()
            val winStart = utcDayStart(0)
            val winEnd = utcDayStart(2)
            val epgEvents = wanted.map { (name, id) ->
                async {
                    withTimeoutOrNull(15_000) {
                        getEpgEvents(id, isoUtc(winEnd), isoUtc(winStart))
                    }.orEmpty().map { it to name }
                }
            }.awaitAll().flatten()
            // Index Yacine events by normalized channel for pairing.
            // Same fixture airs on several channels: merge into one row
            // with the channel union (deduped by fixture + UTC day).
            val yacineByChan = events.groupBy { normChannel(it.channel.orEmpty()) }
            val out = mutableListOf<GuideFixture>()
            epgEvents.forEach { (epg, channelName) ->
                val parsed = parseEpgTitle(epg.title) ?: return@forEach
                val (home, away, league) = parsed
                val epgMs = epgStartMs(epg.startDate) ?: return@forEach
                val yc = yacineByChan[normChannel(channelName)].orEmpty().mapNotNull { ev ->
                    val st = ev.startTime?.times(1000) ?: return@mapNotNull null
                    val dt = abs(st - epgMs)
                    if (dt > 10 * 60 * 1000) return@mapNotNull null
                    ev to dt
                }.minByOrNull { it.second }?.first ?: return@forEach
                val key = "${normTeam(home)}|${normTeam(away)}|${dayOf(epgMs)}"
                val existing = out.firstOrNull {
                    "${normTeam(it.home)}|${normTeam(it.away)}|${dayOf(it.yacine.startTime?.times(1000) ?: epgMs)}" == key
                }
                if (existing != null) {
                    if (existing.channels.none { it.equals(channelName, ignoreCase = true) }) {
                        val idx = out.indexOf(existing)
                        out[idx] = existing.copy(channels = existing.channels + channelName)
                    }
                    return@forEach
                }
                out.add(
                    GuideFixture(
                        home = home,
                        away = away,
                        league = league,
                        channels = listOf(channelName),
                        desc = epg.description?.trim()?.takeIf { it.isNotBlank() },
                        yacine = yc,
                    )
                )
            }
            out.sortedWith(
                compareBy(
                    { matchRank(matchStatus(it.yacine, System.currentTimeMillis() / 1000)) },
                    { it.yacine.startTime ?: Long.MAX_VALUE },
                )
            )
        }
    }

    private fun dayOf(ms: Long): String {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.format(Date(ms))
        } catch (_: Exception) { "" }
    }

    /** Local kickoff with countdown, e.g. "Today 19:45 (in 2h 05m)",
     * "Tomorrow 20:00", "22 Sep, 18:45". Device-local timezone, Latin
     * digits. Empty when already started. */
    private fun formatKickoff(epochSec: Long?, nowSec: Long = System.currentTimeMillis() / 1000): String {
        if (epochSec == null || epochSec <= 0 || epochSec <= nowSec) return ""
        return try {
            val tz = TimeZone.getDefault()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
            val nowDate = dateFmt.format(Date(nowSec * 1000))
            val kickDate = dateFmt.format(Date(epochSec * 1000))
            val time = timeFmt.format(Date(epochSec * 1000))
            val deltaMin = ((epochSec - nowSec) / 60).toInt()
            if (kickDate == nowDate) {
                val cd = when {
                    deltaMin < 60 -> "in ${deltaMin.coerceAtLeast(1)}m"
                    deltaMin < 24 * 60 -> "in ${deltaMin / 60}h ${(deltaMin % 60).toString().padStart(2, '0')}m"
                    else -> ""
                }
                if (cd.isNotEmpty()) "Today $time ($cd)" else "Today $time"
            } else {
                val cal = java.util.Calendar.getInstance(tz).apply {
                    timeInMillis = nowSec * 1000
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                if (kickDate == dateFmt.format(cal.time)) {
                    "Tomorrow $time"
                } else {
                    SimpleDateFormat("dd MMM, HH:mm", Locale.US).apply { timeZone = tz }
                        .format(Date(epochSec * 1000))
                }
            }
        } catch (_: Exception) { "" }
    }

    /** Match state derived from start/end_time vs now. Missing times
     * keep the match playable: unknown start -> LIVE if end not passed. */
    private fun matchStatus(e: YacineEvent, nowSec: Long): String {
        val start = e.startTime?.takeIf { it > 0 }
        val end = e.endTime?.takeIf { it > 0 }
        if (end != null && nowSec > end) return "ENDED"
        if (start != null && nowSec < start) return "UPCOMING"
        return "LIVE"
    }

    /** Cricify-style emoji prefix: 🔴 live / 🔜 upcoming / ✅ ended. */
    private fun statusEmoji(status: String): String = when (status) {
        "LIVE" -> "🔴"
        "UPCOMING" -> "🔜"
        else -> "✅"
    }

    private fun matchRank(status: String): Int = when (status) {
        "LIVE" -> 0
        "UPCOMING" -> 1
        else -> 2
    }

    /** Neutral placeholder banner: the EPG carries no artwork. */
    private val noArtBanner =
        "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/No-Image-Placeholder-banner.svg/960px-No-Image-Placeholder-banner.svg.png"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // All rows fit on one page (no API pagination). Answer single-row
        // drill-ins by name and terminate scrolling: without this, page 2+
        // returns the same full payload and the app appends duplicates.
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
        val nowSec = System.currentTimeMillis() / 1000
        val fixtures = withTimeoutOrNull(60_000) { guideFixtures() } ?: emptyList()
        if (fixtures.isEmpty()) return emptyList()
        // Split rows by UTC day: today vs tomorrow.
        val today = dayOf(nowSec * 1000)
        val (todayFix, laterFix) = fixtures.partition {
            dayOf(it.yacine.startTime?.times(1000) ?: Long.MAX_VALUE) <= today
        }
        return listOf("Today's Matches" to todayFix, "Tomorrow's Matches" to laterFix)
            .mapNotNull { (title, list) ->
                if (list.isEmpty()) return@mapNotNull null
                val items = list.map { f ->
                    val status = matchStatus(f.yacine, nowSec)
                    val link = LinkData(
                        id = f.yacine.id,
                        name = "${statusEmoji(status)} ${f.home} vs ${f.away}",
                        poster = noArtBanner,
                        league = f.league,
                        channel = f.channels.firstOrNull(),
                        commentary = f.yacine.commentary?.trim()?.takeIf { it.isNotBlank() },
                        kickoff = formatKickoff(f.yacine.startTime, nowSec).takeIf { it.isNotBlank() },
                        startTime = f.yacine.startTime,
                        endTime = f.yacine.endTime,
                        desc = f.desc,
                        plot = "${f.home} vs ${f.away}",
                    )
                    val withRelated = link.copy(
                        related = list.filter { it !== f }.take(12).map { rel ->
                            val rs = matchStatus(rel.yacine, nowSec)
                            LinkData(
                                id = rel.yacine.id,
                                name = "${statusEmoji(rs)} ${rel.home} vs ${rel.away}",
                                poster = noArtBanner,
                                league = rel.league,
                                channel = rel.channels.firstOrNull(),
                                commentary = rel.yacine.commentary?.trim()?.takeIf { it.isNotBlank() },
                                kickoff = formatKickoff(rel.yacine.startTime, nowSec).takeIf { it.isNotBlank() },
                                startTime = rel.yacine.startTime,
                                endTime = rel.yacine.endTime,
                                desc = rel.desc,
                                plot = "${rel.home} vs ${rel.away}",
                            )
                        },
                    )
                    newLiveSearchResponse(withRelated.name, withRelated.toJson(), TvType.Live) {
                        this.posterUrl = withRelated.poster
                    }
                }
                HomePageList(title, items, isHorizontalImages = true)
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val nowSec = System.currentTimeMillis() / 1000
        val out = mutableListOf<SearchResponse>()
        withTimeoutOrNull(60_000) { guideFixtures() }.orEmpty()
            .filter { f ->
                listOf("${f.home} vs ${f.away}", f.league.orEmpty(), f.channels.joinToString(" "))
                    .joinToString(" ").contains(q, ignoreCase = true)
            }.take(30)
            .forEach { f ->
                val status = matchStatus(f.yacine, nowSec)
                val link = LinkData(
                    id = f.yacine.id,
                    name = "${statusEmoji(status)} ${f.home} vs ${f.away}",
                    poster = noArtBanner,
                    league = f.league,
                    channel = f.channels.firstOrNull(),
                    commentary = f.yacine.commentary?.trim()?.takeIf { it.isNotBlank() },
                    kickoff = formatKickoff(f.yacine.startTime, nowSec).takeIf { it.isNotBlank() },
                    startTime = f.yacine.startTime,
                    endTime = f.yacine.endTime,
                    desc = f.desc,
                    plot = "${f.home} vs ${f.away}",
                )
                out.add(
                    newLiveSearchResponse(link.name, link.toJson(), TvType.Live) {
                        this.posterUrl = link.poster
                    }
                )
            }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        val nowSec = System.currentTimeMillis() / 1000
        // Fresh emoji + countdown from the baked clocks (no live source).
        val status = if (data.id != null) {
            matchStatus(
                YacineEvent(startTime = data.startTime, endTime = data.endTime),
                nowSec,
            )
        } else null
        val matchup = data.plot?.takeIf { it.isNotBlank() } ?: data.name
        val name = if (status != null) "${statusEmoji(status)} $matchup" else data.name
        val kickoff = data.startTime
            ?.let { formatKickoff(it, nowSec) }?.takeIf { it.isNotBlank() }
            ?: data.kickoff?.takeIf { it.isNotBlank() }
        // Detail description: kickoff, competition, channel, commentator,
        // then the EPG Opta preview. Joined with <br> (not \n): the app
        // renders plot via setTextHtml, which collapses raw newlines.
        val lines = listOfNotNull(
            kickoff?.let { "🕐 $it" },
            data.league?.takeIf { it.isNotBlank() }?.let { "🏆 $it" },
            data.channel?.takeIf { it.isNotBlank() }?.let { "📺 $it" },
            data.commentary?.takeIf { it.isNotBlank() }?.let { "🎙️ $it" },
        )
        val plot = (lines + listOfNotNull(data.desc?.takeIf { it.isNotBlank() }))
            .takeIf { it.isNotEmpty() }?.joinToString("<br><br>")
            ?: data.name
        return newMovieLoadResponse(name, url, TvType.Live, url) {
            this.posterUrl = data.poster ?: noArtBanner
            this.backgroundPosterUrl = data.poster ?: noArtBanner
            this.plot = plot
            data.related?.takeIf { it.isNotEmpty() }?.let { related ->
                this.recommendations = related.map { rel ->
                    newLiveSearchResponse(rel.name, rel.toJson(), TvType.Live) {
                        this.posterUrl = rel.poster
                    }
                }
            }
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

    /** Emits the best variant of a tokenized master playlist with the
     * original query re-attached (?t=&e=).
     * Returns 1 if emitted, 0 if the master is not multivariant (caller
     * falls back to the raw master URL), -1 if it IS multivariant but no
     * variant resolves (caller must NOT emit the master: it has no TS and
     * the player would just error). */
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
            val query = masterUrl.substring(qIndex) // includes '?'
            val base = masterUrl.substring(0, qIndex)
            val master = app.get(masterUrl, headers = headers, timeout = 8).text
            if ("#EXTM3U" !in master) return 0
            if ("#EXT-X-STREAM-INF" !in master) return 0 // media playlist: emit raw
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
            // Absolute variant refs may carry their own query already.
            val variantUrl = if ('?' in rel) {
                if (rel.startsWith("http")) rel else join(base.substringBeforeLast("/"), rel)
            } else {
                val abs = if (rel.startsWith("http")) rel.substringBefore('?')
                else join(base.substringBeforeLast("/"), rel)
                abs + query
            }
            // Sanity: only emit a variant that actually resolves.
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
        val eventId = info.id ?: return false
        val streams = getEventStreams(eventId)
        val tag = info.channel?.trim()?.takeIf { it.isNotEmpty() } ?: info.name
        var found = false
        val seenUrls = mutableSetOf<String>()
        streams.forEach { s ->
            val raw = s.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!seenUrls.add(raw)) return@forEach
            val headers = streamHeaders(s)
            val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val label = if (tag.isNotEmpty() && !serverName.equals(tag, ignoreCase = true)) "$tag • $serverName" else serverName
            val lower = raw.lowercase()
            // url_type 5/6 and other non-media pages are web players, not
            // streams: hand them to the extractor registry.
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
            // Tokenized masters (?t=&e=): players drop the query on relative
            // refs, so resolve the best variant up-front with query kept.
            // A multivariant master with no resolvable variant is unplayable:
            // skip it instead of emitting a link the player errors on.
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
                // Fall through to the raw master URL below (0).
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    label,
                    raw,
                ) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: ""
                    this.quality = qualityFor(label, raw)
                    this.type = if (".m3u8" in raw) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
        }
        return found
    }
}
