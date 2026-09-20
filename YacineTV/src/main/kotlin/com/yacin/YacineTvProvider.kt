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

/** YacineTV: match guide from the official beIN Sports EPG (ar-mena),
 * banners from TheSportsDB (alias-free event search), playback from
 * the Yacine TV API event streams (paired at watch time).
 * No art or live-state upstreams beyond that: status from kickoff
 * clocks, neutral placeholder when no banner resolves. */
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
        @JsonProperty("id") val id: Long? = null, // Yacine event id (v63 links; v64 pairs at watch)
        @JsonProperty("name") val name: String = "",
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("league") val league: String? = null, // EPG competition
        @JsonProperty("channel") val channel: String? = null, // official beIN channel
        @JsonProperty("channels") val channels: List<String> = emptyList(), // all airing channels
        @JsonProperty("commentary") val commentary: String? = null,
        @JsonProperty("kickoff") val kickoff: String? = null, // baked, refreshed at load
        @JsonProperty("kickoffMs") val kickoffMs: Long? = null, // EPG kickoff epoch ms
        @JsonProperty("endMs") val endMs: Long? = null, // EPG slot end epoch ms
        @JsonProperty("startTime") val startTime: Long? = null, // epoch sec (v63 links)
        @JsonProperty("endTime") val endTime: Long? = null, // epoch sec (v63 links)
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

    // Raw Yacine models (slim: catalog + channel streams only).

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

    // Raw Yacine models (slim: catalog + channel streams only).
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

    /** beIN quality groups share channel names across qualities
     * ("beIN SPORTS (1080P/720P/360P/244P)"). Matched by name: category
     * ids rotate (were 4/5/6/7, now 19-digit), names are stable. */
    private val beinQualityRegex = Regex("""be\s*in\s*sports\s*\(?\s*(\d+\s*p)\s*\)?""", RegexOption.IGNORE_CASE)

    private fun isBeinQuality(cat: YacineCategory): Boolean {
        return beinQualityRegex.containsMatchIn(cat.name.orEmpty())
    }

    private fun qualityRank(cat: YacineCategory): Int {
        val m = beinQualityRegex.find(cat.name.orEmpty())
        val num = m?.groupValues?.getOrNull(1)?.replace(Regex("""\D"""), "")?.toIntOrNull()
        // Higher resolution first; unknown quality sorts last.
        return -(num ?: -1)
    }

    private suspend fun getCategories(): List<YacineCategory> {
        val json = getPayload("categories") ?: return emptyList()
        return runCatching {
            parseJson<YacineCategoryEnvelope>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getChannels(categoryId: String): List<YacineChannel> {
        val json = getPayload("categories/$categoryId/channels") ?: return emptyList()
        return runCatching {
            parseJson<YacineChannelResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getChannelStreams(channelId: String): List<YacineStream> {
        // Streams stay fresh-only: stale URLs die fast.
        val (json) = getDecrypted("channel/$channelId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    /** Official EPG name -> Yacine catalog name. The EN channels differ
     * ("beIN SPORTS EN 1" vs "beIN English 1"); without this they never
     * resolve and those fixtures can't play. */
    private val epgToYacineChannel = mapOf(
        "bein sports en 1" to "bein english 1",
        "bein sports en 2" to "bein english 2",
    )

    /** Yacine channel ids for an official EPG channel name, best quality
     * first (one id per quality group). Empty when Yacine carries no such
     * channel. */
    private suspend fun resolveChannelIds(channelName: String): List<String> {
        val want = epgToYacineChannel[normChannel(channelName)] ?: normChannel(channelName)
        if (want.isBlank()) return emptyList()
        val groups = withTimeoutOrNull(20_000) { getCategories() }
            .orEmpty().filter { isBeinQuality(it) }.sortedBy { qualityRank(it) }
        val out = mutableListOf<String>()
        for (g in groups) {
            val gid = g.id.takeIf { it.isNotBlank() } ?: continue
            val hit = withTimeoutOrNull(15_000) { getChannels(gid) }
                .orEmpty().firstOrNull { normChannel(it.name.orEmpty()) == want }
            hit?.id?.takeIf { it.isNotBlank() }?.let { if (it !in out) out.add(it) }
        }
        return out
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

    private data class GuideAiring(
        val channel: String, // official beIN channel name for this airing
        val kickoffMs: Long, // EPG kickoff epoch ms
        val endMs: Long, // program slot end (match + post-match)
    )

    private data class GuideFixture(
        val home: String,
        val away: String,
        val league: String?,
        val desc: String?,
        val airings: List<GuideAiring>, // every airing today, kickoff-sorted
    )

    /** Fallback slot length when the EPG omits endDate: football + margin. */
    private val footballSlotMs = 2 * 3600 * 1000L + 15 * 60 * 1000L

    /** Non-football leagues: the EPG puts MLB, hockey etc. on the core
     * channels too and their "X vs Y" titles parse as fixtures. Blocklist
     * (default-accept): the core channels are overwhelmingly football. */
    private val nonFootballLeague = setOf(
        "mlb", "nba", "nhl", "nfl", "baseball", "basketball", "hockey",
        "tennis", "golf", "cricket", "rugby", "mma", "boxing", "wrestling",
        "f1", "formula 1", "formula1", "motogp", "nascar", "athletics",
        "volleyball", "handball", "badminton", "table tennis", "snooker",
        "darts", "cycling", "swimming", "gymnastics", "ski", "surf",
        "sailing", "rowing", "triathlon", "esports", "poker",
    )

    private fun isFootballLeague(league: String?): Boolean {
        if (league.isNullOrBlank()) return true
        val n = league.lowercase()
        return nonFootballLeague.none { it in n }
    }

    /** EPG core football channels (normalized). NEWS/AFC/MAX/XTRA/FR are
     * filler loops or out of Yacine scope; plain 6 has no EPG entry; 4K
     * has no Yacine feed (unplayable, excluded). */
    private val guideChannelNorms = setOf(
        "bein sports 1", "bein sports 2", "bein sports 3",
        "bein sports 4", "bein sports 5", "bein sports 6",
        "bein sports 7", "bein sports 8", "bein sports 9",
        "bein sports en 1", "bein sports en 2",
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
            fmt.parse(iso)?.time
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

    /** Guide fixtures for today (UTC): EPG football events on the core
     * channels, merged across channels by fixture + day with every airing
     * kept (live + replays). Non-football leagues are dropped. Airings
     * whose slot already ended are dropped: a finished match with an
     * evening replay reads from the replay airing, not 🔴 all afternoon.
     * No Yacine fetch here — pairing happens at watch time, so the guide
     * renders even during Yacine outages. */
    private suspend fun guideFixtures(): List<GuideFixture> {
        return coroutineScope {
            val nowMs = System.currentTimeMillis()
            val channels = withTimeoutOrNull(15_000) { getEpgChannels() } ?: emptyList()
            val wanted = channels.mapNotNull { c ->
                val name = c.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = c.id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (normChannel(name) !in guideChannelNorms) return@mapNotNull null
                name to id
            }.distinct()
            if (wanted.isEmpty()) return@coroutineScope emptyList()
            val winStart = utcDayStart(0)
            val winEnd = utcDayStart(1)
            val epgEvents = wanted.map { (name, id) ->
                async {
                    withTimeoutOrNull(15_000) {
                        getEpgEvents(id, isoUtc(winEnd), isoUtc(winStart))
                    }.orEmpty().map { it to name }
                }
            }.awaitAll().flatten()
            // Same fixture airs on several channels / times: merge into one
            // row with every airing kept (deduped by fixture + UTC day).
            val out = mutableListOf<GuideFixture>()
            epgEvents.forEach { (epg, channelName) ->
                val parsed = parseEpgTitle(epg.title) ?: return@forEach
                val (home, away, league) = parsed
                if (!isFootballLeague(league)) return@forEach
                val epgMs = epgStartMs(epg.startDate) ?: return@forEach
                if (epgMs < winStart || epgMs >= winEnd) return@forEach
                val slotEnd = epg.endDate?.let { epgStartMs(it) }?.takeIf { it > epgMs }
                    ?: (epgMs + footballSlotMs)
                if (slotEnd < nowMs) return@forEach // slot over (finished match)
                val key = "${normTeam(home)}|${normTeam(away)}|${dayOf(epgMs)}"
                val airing = GuideAiring(channelName, epgMs, slotEnd)
                val existing = out.firstOrNull {
                    "${normTeam(it.home)}|${normTeam(it.away)}|${dayOf(it.airings.firstOrNull()?.kickoffMs ?: 0)}" == key
                }
                if (existing != null) {
                    if (existing.airings.none {
                            it.channel.equals(channelName, ignoreCase = true) && it.kickoffMs == epgMs
                        }
                    ) {
                        val idx = out.indexOf(existing)
                        out[idx] = existing.copy(
                            airings = (existing.airings + airing).sortedBy { it.kickoffMs },
                        )
                    }
                    return@forEach
                }
                out.add(
                    GuideFixture(
                        home = home,
                        away = away,
                        league = league,
                        desc = epg.description?.trim()?.takeIf { it.isNotBlank() },
                        airings = listOf(airing),
                    )
                )
            }
            out.sortedWith(
                compareBy(
                    { matchRank(fixtureStatus(it, System.currentTimeMillis())) },
                    { currentAiring(it, System.currentTimeMillis())?.kickoffMs ?: Long.MAX_VALUE },
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

    /** Airing state from the EPG program slot (no live source needed):
     * upcoming before kickoff, live inside the slot, ended after. */
    private fun airingStatus(kickoffMs: Long?, endMs: Long?, nowMs: Long): String {
        if (kickoffMs == null || kickoffMs <= 0 || nowMs < kickoffMs) return "UPCOMING"
        val end = endMs?.takeIf { it > kickoffMs } ?: (kickoffMs + footballSlotMs)
        if (nowMs <= end) return "LIVE"
        return "ENDED"
    }

    /** Fixture state across airings: live if any airing is live, upcoming
     * if any is still ahead, ended when all slots are over. */
    private fun fixtureStatus(f: GuideFixture, nowMs: Long): String {
        var seenUpcoming = false
        for (a in f.airings) {
            when (airingStatus(a.kickoffMs, a.endMs, nowMs)) {
                "LIVE" -> return "LIVE"
                "UPCOMING" -> seenUpcoming = true
            }
        }
        return if (seenUpcoming) "UPCOMING" else "ENDED"
    }

    /** The airing to show and play: the live one, else the next upcoming,
     * else the last one (ended fixtures, old saved links). */
    private fun currentAiring(f: GuideFixture, nowMs: Long): GuideAiring? {
        return f.airings.firstOrNull { nowMs in it.kickoffMs..it.endMs }
            ?: f.airings.firstOrNull { it.kickoffMs > nowMs }
            ?: f.airings.lastOrNull()
    }

    /** Card data for a fixture. Null when every airing is over (the guide
     * hides finished fixtures instead of pinning them 🔴). The card's
     * channel is the relevant airing's channel and it heads the channel
     * list, so playback tries the airing that's actually on now first. */
    private fun fixtureLink(f: GuideFixture, nowMs: Long, poster: String?): LinkData? {
        if (fixtureStatus(f, nowMs) == "ENDED") return null
        val airing = currentAiring(f, nowMs) ?: return null
        val rest = f.airings.map { it.channel }.distinct()
            .filter { !it.equals(airing.channel, ignoreCase = true) }
        return LinkData(
            name = "${statusEmoji(fixtureStatus(f, nowMs))} ${f.home} vs ${f.away}",
            poster = poster,
            league = f.league,
            channel = airing.channel,
            channels = listOf(airing.channel) + rest,
            kickoff = formatKickoff(airing.kickoffMs / 1000, nowMs / 1000).takeIf { it.isNotBlank() },
            kickoffMs = airing.kickoffMs,
            endMs = airing.endMs,
            desc = f.desc,
            plot = "${f.home} vs ${f.away}",
        )
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

    data class SportsDbEvents(
        @JsonProperty("event") val event: List<SportsDbEvent>? = null,
    )

    data class SportsDbEvent(
        @JsonProperty("dateEvent") val dateEvent: String? = null,
        @JsonProperty("strEvent") val strEvent: String? = null,
        @JsonProperty("strThumb") val strThumb: String? = null,
    )

    /** Accent/case-insensitive compare for team names across providers. */
    private fun normThumb(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

    /** TheSportsDB banner for EPG English names, no alias file: both team
     * orders are queried, the day-matching event whose name contains
     * every significant word of both sides wins. Null when nothing
     * day-matches (caller uses the placeholder). */
    private suspend fun searchThumb(home: String, away: String, day: String?): String? {
        if (home.isBlank() || away.isBlank()) return null
        fun words(s: String) = normThumb(s).split(Regex("""\s+"""))
            .map { it.trim() }.filter { it.length > 2 }
        val hw = words(home)
        val aw = words(away)
        if (hw.isEmpty() || aw.isEmpty()) return null
        fun hasAll(ev: String?): Boolean {
            if (ev.isNullOrBlank()) return false
            val n = normThumb(ev)
            return (hw + aw).all { w -> n.contains(w) }
        }
        for ((a, b) in listOf(home to away, away to home)) {
            val q = URLEncoder.encode("${a.replace(' ', '_')}_vs_${b.replace(' ', '_')}", "UTF-8")
            val res = retryIO(times = 2) {
                runCatching {
                    app.get(
                        "https://www.thesportsdb.com/api/v1/json/3/searchevents.php?e=$q",
                        headers = noCacheHeaders,
                        timeout = 8,
                    ).text.takeIf { it.isNotBlank() }
                }.getOrNull()
            } ?: continue
            val events = runCatching { parseJson<SportsDbEvents>(res).event }.getOrNull().orEmpty()
            if (events.isEmpty()) continue
            val pool = if (day.isNullOrBlank()) events else events.filter { it.dateEvent == day }
            pool.firstOrNull { !it.strThumb.isNullOrBlank() && hasAll(it.strEvent) }
                ?.strThumb?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
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
        return coroutineScope {
            val nowMs = System.currentTimeMillis()
            val fixtures = withTimeoutOrNull(60_000) { guideFixtures() } ?: emptyList()
            if (fixtures.isEmpty()) return@coroutineScope emptyList()
            // Banners resolve in parallel; each is guarded so one slow
            // lookup never blocks the homepage.
            val thumbs = fixtures.map { f ->
                async {
                    withTimeoutOrNull(8_000) {
                        // All airings of a fixture share the UTC day; the
                        // banner lookup day-matches on it.
                        searchThumb(f.home, f.away, f.airings.firstOrNull()?.let { dayOf(it.kickoffMs) })
                    }
                }
            }.awaitAll()
        val items = fixtures.zip(thumbs).mapNotNull { (f, thumb) ->
            val link = fixtureLink(f, nowMs, thumb ?: noArtBanner) ?: return@mapNotNull null
            val withRelated = link.copy(
                related = fixtures.mapNotNull { rel ->
                    if (rel.home == f.home && rel.away == f.away) return@mapNotNull null
                    fixtureLink(rel, nowMs, noArtBanner)
                }.take(12),
            )
            newLiveSearchResponse(withRelated.name, withRelated.toJson(), TvType.Live) {
                this.posterUrl = withRelated.poster
            }
        }
        if (items.isEmpty()) return@coroutineScope emptyList()
        listOf(HomePageList("Today's Matches", items, isHorizontalImages = true))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val nowMs = System.currentTimeMillis()
        val out = mutableListOf<SearchResponse>()
        withTimeoutOrNull(60_000) { guideFixtures() }.orEmpty()
            .filter { f ->
                listOf("${f.home} vs ${f.away}", f.league.orEmpty(), f.airings.joinToString(" ") { it.channel })
                    .joinToString(" ").contains(q, ignoreCase = true)
            }.take(30)
            .forEach { f ->
                val link = fixtureLink(f, nowMs, noArtBanner) ?: return@forEach
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
        val nowMs = System.currentTimeMillis()
        // Fresh emoji + countdown from the baked kickoff (no live source).
        // Old v63 links carry Yacine startTime instead of kickoffMs.
        val kickoffMs = data.kickoffMs ?: data.startTime?.times(1000)
        val status = airingStatus(kickoffMs, data.endMs, nowMs)
        val matchup = data.plot?.takeIf { it.isNotBlank() } ?: data.name
        val name = "${statusEmoji(status)} $matchup"
        val kickoff = kickoffMs
            ?.let { formatKickoff(it / 1000, nowMs / 1000) }?.takeIf { it.isNotBlank() }
            ?: data.kickoff?.takeIf { it.isNotBlank() }
        // Banner enrich: TheSportsDB by matchup teams (bounded, detail
        // can afford it). Falls back to the baked poster, then placeholder.
        val matchupTeams = matchup.split(" vs ").map { it.trim() }.takeIf { it.size == 2 }
        val thumb = if (matchupTeams != null) {
            withTimeoutOrNull(5_000) {
                searchThumb(matchupTeams[0], matchupTeams[1], kickoffMs?.let { dayOf(it) })
            }
        } else null
        val banner = thumb ?: data.poster ?: noArtBanner
        // Detail description: kickoff, competition, channel, commentator,
        // then the EPG Opta preview. Joined with <br><br> (not \n): the app
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
            this.posterUrl = banner
            this.backgroundPosterUrl = banner
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
        // Channel playback: the card's channel (the airing that's on now)
        // goes first, then the other airing channels as fallback. First
        // channel with playable streams wins; all its qualities are
        // emitted. v63 links carry only a single channel: it still works
        // as the lone candidate.
        val candidates = (listOfNotNull(info.channel) + info.channels).distinct()
        val streams = candidates.firstNotNullOfOrNull { ch ->
            val ids = withTimeoutOrNull(60_000) { resolveChannelIds(ch) }.orEmpty()
            if (ids.isEmpty()) return@firstNotNullOfOrNull null
            val all = ids.mapNotNull { cid ->
                withTimeoutOrNull(20_000) { getChannelStreams(cid) }
                    ?.takeIf { it.isNotEmpty() }
            }.flatten()
            all.takeIf { it.isNotEmpty() }?.let { ch to it }
        } ?: return false
        val (channelName, channelStreams) = streams
        val tag = channelName.trim().takeIf { it.isNotEmpty() } ?: info.name
        var found = false
        val seenUrls = mutableSetOf<String>()
        channelStreams.forEach { s ->
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
