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

    /** Categories merged into one beIN SPORTS row (one per quality upstream). */
    private val beinQualityIds = setOf(4, 5, 6, 7)
    private val beinQualityRegex = Regex("""be\s*in\s*sports\s*\(?\s*(\d+\s*p)\s*\)?""", RegexOption.IGNORE_CASE)

    data class LinkData(
        @JsonProperty("kind") val kind: String = "channel", // channel | event
        @JsonProperty("ids") val ids: List<Int> = emptyList(), // merged channel ids
        @JsonProperty("id") val id: Long? = null, // event id
        @JsonProperty("name") val name: String = "",
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("poster2") val poster2: String? = null, // match: other team logo
        @JsonProperty("channel") val channel: String? = null, // match: broadcast channel
        @JsonProperty("competition") val competition: String? = null, // match: champions
        @JsonProperty("commentary") val commentary: String? = null, // match: commentator
        @JsonProperty("kickoff") val kickoff: String? = null, // match: formatted start time
        @JsonProperty("team1Id") val team1Id: Int? = null, // match: Yacine team id (lazy art)
        @JsonProperty("team2Id") val team2Id: Int? = null, // match: Yacine team id (lazy art)
        @JsonProperty("startTime") val startTime: Long? = null, // match: epoch sec (lazy art)
        @JsonProperty("related") val related: List<LinkData>? = null, // detail recommendations
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

    private suspend fun getDecrypted(path: String): Pair<String, String>? {
        // The API flaps (connection resets); retry across both bases so one
        // bad call doesn't wipe entire rows (e.g. matches-only homepage).
        return retryIO(times = 3) {
            for (base in listOf(mainUrl, fallbackUrl)) {
                try {
                    val res = app.get(
                        join(base, path),
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        timeout = 10,
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

    // Raw API models (categories + channels share id/name/logo shape).
    // NOTE: /categories and /events have different data shapes, so each
    // endpoint parses its own envelope (no generic shared response type).
    data class YacineChannelResponse(
        @JsonProperty("data") val data: List<YacineChannel>? = null,
    )

    data class YacineCategory(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    data class YacineChannel(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    data class YacineEventResponse(
        @JsonProperty("data") val data: List<YacineEvent>? = null,
    )

    data class YacineEvent(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("champions") val champions: String? = null,
        @JsonProperty("channel") val channel: String? = null,
        @JsonProperty("commentary") val commentary: String? = null,
        @JsonProperty("start_time") val startTime: Long? = null,
        @JsonProperty("end_time") val endTime: Long? = null,
        @JsonProperty("team_1") val team1: YacineTeam? = null,
        @JsonProperty("team_2") val team2: YacineTeam? = null,
    )

    data class YacineTeam(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
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

    private suspend fun getCategories(): List<YacineCategory> {
        val (json) = getDecrypted("categories") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineCategoryEnvelope>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    data class YacineCategoryEnvelope(
        @JsonProperty("data") val data: List<YacineCategory>? = null,
    )

    private suspend fun getChannels(categoryId: Int): List<YacineChannel> {
        val (json) = getDecrypted("categories/$categoryId/channels") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineChannelResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEvents(): List<YacineEvent> {
        val (json) = getDecrypted("events") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineEventResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private fun isBeinQuality(cat: YacineCategory): Boolean {
        if (beinQualityIds.contains(cat.id)) return true
        return cat.name?.let { beinQualityRegex.containsMatchIn(it) } == true
    }

    private fun qualityTag(cat: YacineCategory): String {
        val m = cat.name?.let { Regex("""(\d+\s*P)""", RegexOption.IGNORE_CASE).find(it) }
        if (m != null) return m.groupValues[1].replace(" ", "").uppercase()
        return when (cat.id) {
            4 -> "1080P"; 5 -> "720P"; 6 -> "360P"; 7 -> "244P"
            else -> ""
        }
    }

    private fun normalizeName(n: String): String {
        return n.trim().lowercase()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[-_–—]+"""), " ")
            .trim()
    }

    /** Arabic champions -> English fallback when TheSportsDB has no league
     * (unmapped teams, no day match). Substring matching so minor API
     * wording variants still hit. Returns null when nothing matches. */
    private fun translateChampions(arabic: String?): String? {
        val s = arabic?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val t = s.replace(Regex("""[ً-ٰٟ]"""), "")
        fun has(vararg keys: String) = keys.any { t.contains(it) }
        return when {
            has("أبطال أوروبا", "ابطال اوروبا") -> "UEFA Champions League"
            has("المؤتمر الأوروبي", "المؤتمر الاوروبي") -> "UEFA Conference League"
            has("الأوروبي", "الاوروبي") && has("الدوري") -> "UEFA Europa League"
            has("الإنجليزي", "الانجليزي", "البريميرليج") -> "Premier League"
            has("الإسباني", "الاسباني", "الليجا") -> "La Liga"
            has("الإيطالي", "الايطالي", "الكالتشيو") -> "Serie A"
            has("الألماني", "الالماني", "البوندسليجا") -> "Bundesliga"
            has("الفرنسي") -> "Ligue 1"
            has("السعودي", "روشن") -> "Saudi Pro League"
            has("المغربي", "البطولة الاحترافية", "البطولة") -> "Botola Pro"
            has("المصري") -> "Egyptian Premier League"
            has("أبطال أفريقيا", "ابطال افريقيا") -> "CAF Champions League"
            has("أبطال آسيا", "ابطال اسيا") -> "AFC Champions League"
            has("أمم أفريقيا", "امم افريقيا") -> "Africa Cup of Nations"
            has("أمم أوروبا", "امم اوروبا") -> "UEFA Euro"
            has("كأس العالم", "كاس العالم") -> "FIFA World Cup"
            else -> null
        }
    }

    /** Preferred competition label: TheSportsDB English league, then the
     * Arabic->English map, then raw Arabic (never blank). */
    private fun competitionEnglish(league: String?, champions: String?): String? {
        league?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = champions?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return translateChampions(raw) ?: raw
    }

    private fun cleanCategoryName(name: String?): String {
        val n = (name ?: "أخرى").trim()
        if (beinQualityRegex.containsMatchIn(n)) return "beIN SPORTS"
        if (normalizeName(n) == "mbc channels") return "MBC Channels"
        return n
    }

    /** Local kickoff with countdown, e.g. "Today 19:45 (in 2h 05m)",
     * "Tomorrow 20:00", "22 Sep, 18:45". Device-local timezone, Latin
     * digits. Empty when not upcoming (live cards show the minute, ended
     * cards show FT) so detail tags stay relevant. */
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

    private fun eventTitle(e: YacineEvent): String {
        val t1 = e.team1?.name?.trim().orEmpty()
        val t2 = e.team2?.name?.trim().orEmpty()
        if (t1.isNotBlank() && t2.isNotBlank()) return "$t1 × $t2"
        return e.champions?.trim().orEmpty().ifBlank { "مباراة" }
    }

    private fun eventEnglishTitle(e: YacineEvent): String? {
        // ID -> English via team_aliases.json (remote, no extra network here).
        // Null when either side is unmapped -> caller falls back to Arabic.
        val a = teamAlias(e.team1?.id)?.trim().takeIf { !it.isNullOrBlank() }
        val b = teamAlias(e.team2?.id)?.trim().takeIf { !it.isNullOrBlank() }
        if (a.isNullOrBlank() || b.isNullOrBlank()) return null
        if (a == b) return a
        return "$a vs $b"
    }

    private fun eventBaseTitle(e: YacineEvent): String {
        return eventEnglishTitle(e) ?: eventTitle(e)
    }

    private fun matchPlot(title: String): String {
        return "شاهد البث المباشر لمباراة $title"
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

    /** Display status: live TheSportsDB state wins over Yacine clocks
     * (clock skew / missing times); otherwise Yacine start/end_time. */
    private fun displayStatus(e: YacineEvent, nowSec: Long, state: MatchState?): String {
        val s = state?.status?.trim()?.uppercase()
        if (s != null) {
            if (isInPlayScore(s)) return "LIVE"
            if (isFinalScore(s)) return "ENDED"
        }
        return matchStatus(e, nowSec)
    }

    /** Minute tag for live cards: 65' | HT | FT | LIVE | null. */
    private fun minuteLabel(state: MatchState?): String? {
        val s = state ?: return null
        val raw = s.status?.trim()?.uppercase() ?: return null
        if (isFinalScore(raw)) return "FT"
        if (raw == "HT") return "HT"
        if (!isInPlayScore(raw)) return null
        val p = s.progress?.trim()?.takeIf { it.isNotBlank() } ?: return "LIVE"
        return if (p[0].isDigit() && !p.endsWith("'")) "$p'" else p
    }

    /** Card title, option (a): score inline when TheSportsDB has it on the
     * day-matching fixture, e.g. "🔴 65' Everton 1-0 Wolves",
     * "✅ FT Coventry City 2-1 Aston Villa". Scores only ever pair with
     * orderedTitle (canonical home-first); without it, plain "A vs B". */
    private fun eventDisplayName(e: YacineEvent, nowSec: Long, art: MatchArt? = null): String {
        val matchup = art?.orderedTitle ?: eventBaseTitle(e)
        val teams = art?.orderedTitle?.split(" vs ")?.takeIf { it.size == 2 }
        val st = art?.state
        val hs = st?.homeScore
        val aws = st?.awayScore
        val status = displayStatus(e, nowSec, st)
        val emoji = statusEmoji(status)
        if (teams != null && hs != null && aws != null) {
            val score = "${teams[0]} $hs-$aws ${teams[1]}"
            return when (status) {
                "LIVE" -> {
                    val tag = if (st?.status?.trim()?.uppercase() == "HT") "HT" else (minuteLabel(st) ?: "LIVE")
                    "$emoji $tag $score"
                }
                "UPCOMING" -> "$emoji $matchup"
                else -> {
                    val tag = if (isFinalScore(st?.status)) "FT " else ""
                    "$emoji $tag$score"
                }
            }
        }
        return when (status) {
            "LIVE" -> {
                val min = minuteLabel(st)
                if (min != null) "$emoji $min $matchup" else "$emoji $matchup"
            }
            else -> "$emoji $matchup"
        }
    }

    private fun matchRank(status: String): Int = when (status) {
        "LIVE" -> 0
        "UPCOMING" -> 1
        else -> 2
    }

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
            runCatching { ensureTeamAliases() }
            val eventsDeferred = async { getEvents() }
            val categories = getCategories()

            val beinCats = categories.filter { isBeinQuality(it) }
            val otherCats = categories.filter { !isBeinQuality(it) }

            // Fetch beIN quality groups in parallel, then merge by channel name.
            val beinGroups = beinCats.map { cat ->
                async {
                    cat to getChannels(cat.id)
                }
            }.awaitAll()

            val lists = mutableListOf<HomePageList>()

            // 1) Matches first (horizontal cards with API banners).
            // Ended matches stay visible with an [ENDED] label.
            val nowSec = System.currentTimeMillis() / 1000
            val events = eventsDeferred.await().sortedWith(
                compareBy(
                    { matchRank(matchStatus(it, nowSec)) },
                    { it.startTime ?: Long.MAX_VALUE },
                    { -(it.endTime ?: Long.MIN_VALUE) },
                )
            )
            if (events.isNotEmpty()) {
                // Thumbs/badges/leagues resolve in parallel; each is guarded
                // so one slow lookup never blocks the homepage.
                // TheSportsDB art (thumb + English strLeague) reuses one request.
                // Cricify-style: no cache, every card fetches fresh.
                val arts = events.map { e ->
                    async {
                        // No team-logo fallback: cards show the TheSportsDB
                        // banner or render empty (null poster).
                        val art = withTimeoutOrNull(8_000) { matchArt(e) } ?: MatchArt()
                        art to art.thumb
                    }
                }.awaitAll()
                val matchLinks = events.zip(arts).mapNotNull { (e, artAndPoster) ->
                    val (art, poster) = artAndPoster
                    val league = art.league
                    val id = e.id ?: return@mapNotNull null
                    // Home-first matchup from TheSportsDB when day-matched;
                    // Yacine team_1/team_2 order is not reliable. The card
                    // title adds live minute + score inline (option a).
                    val matchup = art.orderedTitle ?: eventBaseTitle(e)
                    val displayName = eventDisplayName(e, nowSec, art)
                    // TheSportsDB banner or empty (no team-logo fallback);
                    // poster2 stays null so detail shows banner or empty.
                    LinkData(
                        kind = "event",
                        id = id,
                        name = displayName,
                        poster = poster,
                        poster2 = null,
                        channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                        competition = competitionEnglish(league, e.champions),
                        commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                        kickoff = formatKickoff(e.startTime, nowSec).takeIf { it.isNotBlank() },
                        team1Id = e.team1?.id,
                        team2Id = e.team2?.id,
                        startTime = e.startTime,
                        plot = matchPlot(matchup),
                    )
                }
                // Detail recommendations: the other matches (no extra network).
                val matchItems = matchLinks.map { link ->
                    val withRelated = link.copy(
                        related = matchLinks.filter { it.id != link.id }.take(12),
                    )
                    newLiveSearchResponse(link.name, withRelated.toJson(), TvType.Live) {
                        this.posterUrl = link.poster
                    }
                }
                if (matchItems.isNotEmpty()) {
                    lists.add(HomePageList("Today's Matches", matchItems, isHorizontalImages = true))
                }
            }

            // 2) Single merged beIN SPORTS row (quality picked in player sources).
            if (beinGroups.isNotEmpty()) {
                val merged = linkedMapOf<String, MergedChannel>()
                beinGroups.forEach { (cat, channels) ->
                    val tag = qualityTag(cat)
                    channels.forEach { ch ->
                        val cid = ch.id ?: return@forEach
                        val nm = ch.name?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                        val key = normalizeName(nm)
                        val cur = merged[key]
                        if (cur == null) {
                            merged[key] = MergedChannel(nm, ch.logo, mutableListOf(cid to tag))
                        } else {
                            if (cur.ids.none { it.first == cid }) cur.ids.add(cid to tag)
                            if (cur.logo.isNullOrBlank() && !ch.logo.isNullOrBlank()) cur.logo = ch.logo
                        }
                    }
                }
                val beinLinks = merged.values.map { m ->
                    val logo = m.logo?.takeIf { it.isNotBlank() }
                    LinkData(
                        kind = "channel",
                        ids = m.ids.map { it.first }.distinct(),
                        name = m.name,
                        poster = logo,
                        plot = "شاهد البث المباشر لقناة ${m.name}",
                    )
                }
                // Detail recommendations: row siblings (no extra network).
                val beinItems = beinLinks.map { link ->
                    val withRelated = link.copy(
                        related = beinLinks.filter { it.name != link.name }.take(12),
                    )
                    newLiveSearchResponse(link.name, withRelated.toJson(), TvType.Live) {
                        this.posterUrl = link.poster
                    }
                }
                if (beinItems.isNotEmpty()) {
                    lists.add(HomePageList("beIN SPORTS", beinItems, isHorizontalImages = true))
                }
            }

            // 3) Curated homepage rows only:
            // Today's Matches, beIN SPORTS, Morocco Channels, MBC Channels.
            val wantedTopRows = setOf("mbc channels")
            val otherRows = otherCats.mapNotNull { cat ->
                val norm = normalizeName(cat.name ?: "")
                when {
                    norm == "arabic channels" || cat.id == 9 -> cat to true // parent
                    wantedTopRows.contains(norm) -> cat to false
                    else -> null // dropped: entertainment, france, turkish, weyyak, shahid, other countries
                }
            }.map { (cat, isParent) ->
                async {
                    if (!isParent) {
                        val channels = getChannels(cat.id)
                        if (channels.isEmpty()) return@async emptyList()
                        return@async listOfNotNull(channelRow(cleanCategoryName(cat.name), channels))
                    }
                    // Morocco only (id 15) from the ARABIC parent.
                    val subs = getSubcategories(cat.id)
                    val morocco = subs.firstOrNull {
                        it.id == 15 || normalizeName(it.name ?: "") == "morocco"
                    } ?: return@async emptyList()
                    val subChannels = getChannels(morocco.id)
                    if (subChannels.isEmpty()) return@async emptyList()
                    listOfNotNull(channelRow("Moroccan Channels", subChannels, moroccoThumbs))
                }
            }.awaitAll().flatten()

            lists.addAll(otherRows)
            lists
        }
    }

    /** Yacine team id -> English alias for TheSportsDB lookups.
     * Cricify-style: fetched fresh from remote on every build, no
     * memory/disk cache. Unknown IDs skip the thumbnail API and fall back
     * to API logos. */
    private val teamAliasesUrl =
        "https://raw.githubusercontent.com/clearpath-mind/cs-extensions/main/YacineTV/team_aliases.json"

    @Volatile
    private var teamAliases: Map<Int, String>? = null

    private fun teamAlias(id: Int?): String? {
        if (id == null) return null
        return teamAliases?.get(id)
    }

    /** Fetches the team id -> alias map from the remote JSON on every call.
     * Never throws: any failure keeps the last map (possibly empty). */
    private suspend fun ensureTeamAliases() {
        fetchRemoteAliases()?.takeIf { it.isNotEmpty() }?.let {
            teamAliases = it
            return
        }
        if (teamAliases == null) teamAliases = emptyMap()
    }

    private suspend fun fetchRemoteAliases(): Map<Int, String>? {
        return retryIO(times = 2) {
            app.get(
                teamAliasesUrl,
                headers = noCacheHeaders,
                timeout = 8,
            ).text.takeIf { it.isNotBlank() }?.let { parseAliasesJson(it) }
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseAliasesJson(json: String): Map<Int, String>? {
        return runCatching {
            parseJson<Map<String, String>>(json)
                .mapNotNull { (k, v) ->
                    val id = k.trim().toIntOrNull() ?: return@mapNotNull null
                    val alias = v.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    id to alias
                }.toMap()
        }.getOrNull()
    }

    data class SportsDbEvents(
        @JsonProperty("event") val event: List<SportsDbEvent>? = null,
    )

    data class SportsDbEvent(
        @JsonProperty("dateEvent") val dateEvent: String? = null,
        @JsonProperty("strEvent") val strEvent: String? = null,
        @JsonProperty("strHomeTeam") val strHomeTeam: String? = null,
        @JsonProperty("strAwayTeam") val strAwayTeam: String? = null,
        @JsonProperty("strThumb") val strThumb: String? = null,
        @JsonProperty("strLeague") val strLeague: String? = null,
        @JsonProperty("strStatus") val strStatus: String? = null,
        @JsonProperty("strProgress") val strProgress: String? = null,
        @JsonProperty("intHomeScore") val intHomeScore: String? = null,
        @JsonProperty("intAwayScore") val intAwayScore: String? = null,
        @JsonProperty("strTimestamp") val strTimestamp: String? = null,
    )

    /** Ready-made 1280x720 match banner + English league from TheSportsDB
     * (free key). Cricify-style: no cache — every build fetches fresh
     * (Cache-Control: no-cache), same request also carrying the home-first
     * title and live state. No banner renders empty (null poster, no
     * team-logo fallback); league falls back to Arabic champions.
     * orderedTitle is the home-first "A vs B" from TheSportsDB
     * (strHomeTeam/strAwayTeam, else strEvent order): Yacine team_1/team_2
     * is not reliably home-first (e.g. 2026-09-16 Everton-Wolves and
     * Coventry-Aston Villa were both reversed upstream). */
    private data class MatchArt(
        val thumb: String? = null,
        val league: String? = null,
        val orderedTitle: String? = null,
        val state: MatchState? = null,
    )
    /** Live score/progress from the day-matching TheSportsDB event.
     * Scores always follow the canonical home-first order (same fixture
     * as orderedTitle), never Yacine team_1/team_2 order. Scores parse
     * from String because the API mixes "2" and 2. */
    private data class MatchState(
        val status: String? = null, // NS | 1H | HT | 2H | ET | FT ...
        val progress: String? = null, // e.g. 65'
        val homeScore: Int? = null,
        val awayScore: Int? = null,
    )
    private fun isInPlayScore(s: String?) =
        s?.trim()?.uppercase() in setOf("1H", "HT", "2H", "ET", "BT", "P")
    private fun isFinalScore(s: String?) =
        s?.trim()?.uppercase() in setOf("FT", "AET", "AP")
    /** Cricify parity: every data request opts out of HTTP caching. */
    private val noCacheHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Cache-Control" to "no-cache, no-store",
    )

    /** Accent/case-insensitive compare for team names across APIs. */
    private fun normArt(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

    /** Home-first "A vs B" from a TheSportsDB event. a/b are our aliases
     * (proper casing for display). Prefers strHomeTeam/strAwayTeam exact
     * match, falls back to whichever alias comes first in strEvent. */
    private fun orderedTitleFor(ev: SportsDbEvent, a: String, b: String): String? {
        val na = normArt(a)
        val nb = normArt(b)
        if (na.isBlank() || nb.isBlank() || na == nb) return null
        val home = ev.strHomeTeam?.let { normArt(it) }?.takeIf { it.isNotBlank() }
        val away = ev.strAwayTeam?.let { normArt(it) }?.takeIf { it.isNotBlank() }
        if (home == na && away == nb) return "$a vs $b"
        if (home == nb && away == na) return "$b vs $a"
        val n = ev.strEvent?.let { normArt(it) } ?: return null
        if (!n.contains(na) || !n.contains(nb)) return null
        return if (n.indexOf(nb) < n.indexOf(na)) "$b vs $a" else "$a vs $b"
    }

    private suspend fun matchThumb(e: YacineEvent): String? {
        return matchArt(e).thumb
    }

    private suspend fun matchLeague(e: YacineEvent): String? {
        return matchArt(e).league
    }

    /** Cricify-style: no art/state cache — every build fetches fresh
     * (Cache-Control: no-cache), same request returning thumb + league +
     * home-first title + live state. Bounded by caller timeouts. */
    private suspend fun matchArt(e: YacineEvent): MatchArt {
        e.id ?: return MatchArt()
        val day = e.startTime?.let { dayString(it) }
        val t1 = teamAlias(e.team1?.id) ?: return MatchArt()
        val t2 = teamAlias(e.team2?.id) ?: return MatchArt()
        // Both team orders are always consulted: the first order often
        // returns a league-only result (day pool empty, league falls back to
        // any leg) while the reversed order holds the day-matching banner.
        // Returning early on league-only is what left cards on logo fallback.
        var bestThumb: String? = null
        var bestLeague: String? = null
        var bestTitle: String? = null
        var bestState: MatchState? = null
        for ((a, b) in listOf(t1 to t2, t2 to t1)) {
            val art = searchEventArt(a, b, day)
            if (art.thumb != null && bestThumb == null) bestThumb = art.thumb
            if (art.league != null && bestLeague == null) bestLeague = art.league
            if (art.orderedTitle != null && bestTitle == null) bestTitle = art.orderedTitle
            if (art.state != null && bestState == null) bestState = art.state
            if (bestThumb != null && bestLeague != null && bestTitle != null && bestState != null) break
        }
        // No backoff cache: empty/failed lookups simply return nothing and
        // are retried on the next build (Cricify-style always-fresh).
        if (bestThumb == null && bestLeague == null) {
            return MatchArt(null, null, bestTitle, bestState)
        }
        return MatchArt(bestThumb, bestLeague, bestTitle, bestState)
    }

    private fun dayString(epochSec: Long): String {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.format(Date(epochSec * 1000))
        } catch (_: Exception) { "" }
    }

    private suspend fun searchEventArt(a: String, b: String, day: String?): MatchArt {
        // Shared free key flaps (000s); retry the request, but a clean
        // response with no day-matching art is final (no pointless retry).
        val q = URLEncoder.encode("${a.replace(' ', '_')}_vs_${b.replace(' ', '_')}", "UTF-8")
        val res = retryIO(times = 2) {
            app.get(
                "https://www.thesportsdb.com/api/v1/json/3/searchevents.php?e=$q",
                headers = noCacheHeaders,
                timeout = 8,
            ).text.takeIf { it.isNotBlank() }
        } ?: return MatchArt()
        val events = runCatching { parseJson<SportsDbEvents>(res).event }.getOrNull().orEmpty()
        if (events.isEmpty()) return MatchArt()
        // Thumb stays day-strict (wrong-leg banners are worse than none),
        // but league is the same across legs so it falls back to any event.
        val pool = if (day.isNullOrBlank()) events else events.filter { it.dateEvent == day }
        // Prefer the banner whose event name contains BOTH teams
        // (accents/case-insensitive): the query can return other legs sharing
        // one side, and the first day-match is not always ours.
        val na = normArt(a)
        val nb = normArt(b)
        fun hasBoth(ev: String?): Boolean {
            if (ev.isNullOrBlank()) return false
            val n = normArt(ev)
            return n.contains(na) && n.contains(nb)
        }
        val thumbEvent = pool.firstOrNull {
            !it.strThumb.isNullOrBlank() && hasBoth(it.strEvent)
        } ?: pool.firstOrNull { !it.strThumb.isNullOrBlank() }
        val thumb = thumbEvent?.strThumb
        val league = pool.firstOrNull { !it.strLeague.isNullOrBlank() }?.strLeague?.trim()
            ?: events.firstOrNull { !it.strLeague.isNullOrBlank() }?.strLeague?.trim()
        // Home-first title from the day-matching fixture (thumb event when
        // present, else any both-teams pool event): Yacine order is not
        // reliable, TheSportsDB strHomeTeam/strAwayTeam is.
        val orderSource = thumbEvent
            ?: pool.firstOrNull { hasBoth(it.strEvent) }
            ?: pool.firstOrNull()
        val orderedTitle = orderSource?.let { orderedTitleFor(it, a, b) }
        // Live state from the same fixture (scores follow canonical
        // home-first order, same as orderedTitle). Null when no day match:
        // callers fall back to Yacine clocks, no score shown.
        fun num(v: String?) =
            v?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }?.toIntOrNull()
        val state = orderSource?.let { src ->
            MatchState(
                status = src.strStatus?.trim()?.takeIf { it.isNotBlank() },
                progress = src.strProgress?.trim()?.takeIf { it.isNotBlank() },
                homeScore = num(src.intHomeScore),
                awayScore = num(src.intAwayScore),
            ).takeIf { s ->
                s.status != null || s.progress != null || s.homeScore != null || s.awayScore != null
            }
        }
        return MatchArt(thumb, league, orderedTitle, state)
    }

    private suspend fun searchEventThumb(a: String, b: String, day: String?): String? {
        return searchEventArt(a, b, day).thumb
    }

    data class SportsDbTeams(
        @JsonProperty("teams") val teams: List<SportsDbTeam>? = null,
    )

    data class SportsDbTeam(
        @JsonProperty("strTeam") val strTeam: String? = null,
        @JsonProperty("strBadge") val strBadge: String? = null,
    )

    /** 500px team badge (much sharper than the 96px API logos) used as the
     * card poster when no event thumb exists. Cricify-style: fetched fresh
     * every time, no cache. */
    private suspend fun teamBadge(englishName: String): String? {
        if (englishName.isBlank()) return null
        return retryIO(times = 2) {
            val q = URLEncoder.encode(englishName, "UTF-8")
            val res = app.get(
                "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=$q",
                headers = noCacheHeaders,
                timeout = 8,
            ).text.takeIf { it.isNotBlank() } ?: return@retryIO null
            runCatching { parseJson<SportsDbTeams>(res).teams }
                .getOrNull()
                .orEmpty()
                .filter { !it.strBadge.isNullOrBlank() }
                .firstOrNull {
                    it.strTeam?.trim().equals(englishName, ignoreCase = true)
                }?.strBadge
                ?: return@retryIO null
        }
    }

    /** Official snrtlive.ma vignette arts for the SNRT channels
     * (verified 200; ~5-7 KB each). Other Morocco entries (2M, Medi 1,
     * Télé Maroc) keep API logos. Keys are normalized channel names. */
    private val moroccoThumbs = mapOf(
        "2m" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/29/2M_TV_logo.svg/1280px-2M_TV_logo.svg.png",
        "al aoula" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-03/alaoula-16x9.jpeg",
        "laayoune" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/laayoune-16x9.jpeg",
        "arryadia" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/arriyadia-16x9.jpeg",
        "arryadia tv" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/arriyadia-16x9.jpeg",
        "athaqafia" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/attakafiya-16x9.jpeg",
        "athaqafia tv" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/attakafiya-16x9.jpeg",
        "al maghribia" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/almaghribia-16x9.jpeg",
        "assadissa" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/assadissa-16x9.jpeg",
        "tamazight" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/tamazight-16x9.jpeg",
        "tamazight tv" to "https://snrtlive.ma/sites/default/files/styles/vignette/public/2023-04/tamazight-16x9.jpeg",
    )

    /** Single horizontal channel row, deduped by normalized name. API logos as-is. */
    private suspend fun channelRow(
        title: String,
        channels: List<YacineChannel>,
        thumbOverrides: Map<String, String>? = null,
    ): HomePageList? {
        val seen = linkedMapOf<String, YacineChannel>()
        channels.forEach { ch ->
            val nm = ch.name?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            seen.putIfAbsent(normalizeName(nm), ch)
        }
        val links = seen.values.mapNotNull { ch ->
            val cid = ch.id ?: return@mapNotNull null
            val nm = ch.name?.trim() ?: return@mapNotNull null
            val logo = thumbOverrides?.get(normalizeName(nm))?.takeIf { it.isNotBlank() }
                ?: ch.logo?.takeIf { it.isNotBlank() }
            LinkData(
                kind = "channel",
                ids = listOf(cid),
                name = nm,
                poster = logo,
                plot = "شاهد البث المباشر لقناة $nm",
            )
        }
        // Detail recommendations: row siblings (no extra network).
        val items = links.map { link ->
            val withRelated = link.copy(
                related = links.filter { it.name != link.name }.take(12),
            )
            newLiveSearchResponse(link.name, withRelated.toJson(), TvType.Live) {
                this.posterUrl = link.poster
            }
        }
        if (items.isEmpty()) return null
        return HomePageList(title, items, isHorizontalImages = true)
    }

    /** Child categories of a parent (e.g. ARABIC CHANNELS -> 20 countries). */
    private suspend fun getSubcategories(categoryId: Int): List<YacineCategory> {
        val (json) = getDecrypted("categories/$categoryId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineCategoryEnvelope>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }
    private data class MergedChannel(
        val name: String,
        var logo: String?,
        val ids: MutableList<Pair<Int, String>>,
    )

    private data class SearchHit(
        val name: String,
        var poster: String?,
        val ids: MutableList<Int>,
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            runCatching { ensureTeamAliases() }
            val eventsDeferred = async { getEvents() }
            val q = query.trim()

            // Server-side global channel search (Latin only, all quality
            // groups incl. XTRA/RMC/DAZN): one request instead of crawling
            // every curated category. Events are still matched locally below.
            val channelHits = runCatching {
                val (json) = getDecrypted("search?query=${URLEncoder.encode(q, "UTF-8")}")
                    ?: return@runCatching emptyList<YacineChannel>()
                if (json.isBlank()) return@runCatching emptyList<YacineChannel>()
                parseJson<YacineChannelResponse>(json).data ?: emptyList()
            }.getOrNull() ?: emptyList()

            val out = mutableListOf<SearchResponse>()
            // Merge hits by channel name so beIN search results keep all quality ids.
            val mergedHits = linkedMapOf<String, SearchHit>()
            channelHits.forEach { ch ->
                val nm = ch.name?.trim() ?: return@forEach
                val cid = ch.id ?: return@forEach
                val key = "c:${normalizeName(nm)}"
                val cur = mergedHits[key]
                if (cur == null) {
                    mergedHits[key] = SearchHit(
                        nm,
                        moroccoThumbs[normalizeName(nm)]?.takeIf { it.isNotBlank() }
                            ?: ch.logo,
                        mutableListOf(cid),
                    )
                } else {
                    if (!cur.ids.contains(cid)) cur.ids.add(cid)
                    if (cur.poster.isNullOrBlank() && !ch.logo.isNullOrBlank()) cur.poster = ch.logo
                }
            }
            mergedHits.values.forEach { hit ->
                val logo = hit.poster?.takeIf { it.isNotBlank() }
                val data = LinkData(
                    kind = "channel",
                    ids = hit.ids.toList(),
                    name = hit.name,
                    poster = logo,
                    plot = "شاهد البث المباشر لقناة ${hit.name}",
                ).toJson()
                out.add(
                    newLiveSearchResponse(hit.name, data, TvType.Live) {
                        this.posterUrl = logo
                    }
                )
            }

            val nowSec = System.currentTimeMillis() / 1000
            val matched = eventsDeferred.await().filter { e ->
                val title = eventBaseTitle(e)
                val arabicTitle = eventTitle(e)
                val displayName = eventDisplayName(e, nowSec)
                val hay = listOfNotNull(title, arabicTitle, displayName, e.champions, e.channel, e.team1?.name, e.team2?.name)
                    .joinToString(" ")
                hay.contains(q, ignoreCase = true)
            }
            // English league + home-first title + live state per match
            // (cached; bounded lookup so search stays fast).
            val artMap = matched.map { e ->
                async { e.id to withTimeoutOrNull(4_000) { matchArt(e) } }
            }.awaitAll().toMap()
            matched.forEach { e ->
                val art = artMap[e.id]
                val matchup = art?.orderedTitle ?: eventBaseTitle(e)
                val displayName = eventDisplayName(e, nowSec, art)
                val id = e.id ?: return@forEach
                // TheSportsDB banner or empty (no team-logo fallback).
                val poster = art?.thumb
                val data = LinkData(
                    kind = "event",
                    id = id,
                    name = displayName,
                    poster = poster,
                    poster2 = null,
                    channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                    competition = competitionEnglish(art?.league, e.champions),
                    commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                    kickoff = formatKickoff(e.startTime, nowSec).takeIf { it.isNotBlank() },
                    team1Id = e.team1?.id,
                    team2Id = e.team2?.id,
                    startTime = e.startTime,
                    plot = matchPlot(matchup),
                ).toJson()
                out.add(
                    newLiveSearchResponse(displayName, data, TvType.Live) {
                        this.posterUrl = poster
                    }
                )
            }
            out
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        // Lazy art enrich for non-priority cards (homepage renders those
        // cache-only): resolves banner + live state on detail open and warms
        // the cache for the next homepage build. Bounded so detail stays fast.
        val nowSec = System.currentTimeMillis() / 1000
        val lazyEvent = if (data.kind == "event" && data.id != null &&
            (data.team1Id != null || data.team2Id != null)
        ) {
            YacineEvent(
                id = data.id,
                startTime = data.startTime,
                team1 = data.team1Id?.let { YacineTeam(it) },
                team2 = data.team2Id?.let { YacineTeam(it) },
            )
        } else null
        val lazyArt = lazyEvent?.let { ev ->
            withTimeoutOrNull(5_000) {
                runCatching { ensureTeamAliases() }
                matchArt(ev)
            }
        }
        // Fresh title only when the canonical home-first matchup resolved;
        // otherwise the baked card name (rebuilding from id-only teams
        // would degrade to "مباراة").
        val name = if (lazyArt?.orderedTitle != null && lazyEvent != null) {
            eventDisplayName(lazyEvent, nowSec, lazyArt)
        } else data.name
        val banner = data.poster ?: lazyArt?.thumb
        // Match meta, in order: status, competition, kickoff,
        // commentator, broadcast channel. Accepts new emoji prefix
        // (🔴/🔜/✅) and legacy [LIVE]/[UPCOMING]/[ENDED] saved links.
        // Old saved links carry Arabic champions + old date format;
        // re-translate so detail shows English without re-adding.
        val status = if (data.kind == "event") when {
            name.startsWith("🔴") -> "LIVE"
            name.startsWith("🔜") -> "UPCOMING"
            name.startsWith("✅") -> "ENDED"
            else -> Regex("""^\[(LIVE|UPCOMING|ENDED)\]""").find(name)?.groupValues?.get(1)
        } else null
        val competition = if (data.kind == "event") {
            lazyArt?.league?.takeIf { it.isNotBlank() }
                ?: competitionEnglish(null, data.competition)
        } else null
        // Fresh countdown when the kickoff epoch rode along;
        // otherwise the baked value (old saved links).
        val kickoff = if (data.kind == "event") {
            data.startTime
                ?.let { formatKickoff(it, nowSec) }?.takeIf { it.isNotBlank() }
                ?: data.kickoff?.takeIf { it.isNotBlank() }
        } else null
        // Cricify-style emoji plot, no tags: one line per available
        // field in fixed order. UPCOMING gets no status line (the kickoff
        // line covers it); channels keep their watch plot below.
        val matchPlotLines = if (data.kind == "event") listOfNotNull(
            when (status) {
                "LIVE" -> "🔴 مباشر الآن"
                "ENDED" -> "✅ انتهت المباراة"
                else -> null
            },
            competition?.let { "🏆 $it" },
            kickoff?.let { "🕐 $it" },
            data.commentary?.takeIf { it.isNotBlank() }?.let { "🎙️ $it" },
            data.channel?.takeIf { it.isNotBlank() }?.let { "📺 $it" },
        ) else emptyList()
        val plot = matchPlotLines.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            ?: data.plot
            ?: if (data.kind == "event") matchPlot(name)
            else "شاهد البث المباشر لقناة ${data.name}"
        return newMovieLoadResponse(name, url, TvType.Live, url) {
            this.posterUrl = banner
            // Matches: hero shows the same homepage thumbnail (banner);
            // empty when no banner was rendered (no team-logo fallback).
            if (data.kind == "event") {
                this.backgroundPosterUrl = banner ?: data.poster2
            }
            this.plot = plot
            // Recommendations ride in LinkData (row siblings / other matches).
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
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        out["User-Agent"] = ua
        val ref = s.referer?.takeIf { it.isNotBlank() } ?: out["Referer"]
        if (!ref.isNullOrBlank()) out["Referer"] = ref
        return out
    }

    private suspend fun getChannelStreams(channelId: Int): List<YacineStream> {
        val (json) = getDecrypted("channel/$channelId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEventStreams(eventId: Long): List<YacineStream> {
        // /event/{id} and /event/{id}/servers return the same server list.
        val (json) = getDecrypted("event/$eventId") ?: getDecrypted("event/$eventId/servers") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
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

    /** Verified direct EdgeNext CDN streams (iptv-org) used when the API has
     * nothing playable for an MBC channel (empty data or dead embeds).
     * Keys are normalized channel names. */
    private val mbcFallbacks = mapOf(
        "mbc 1" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1/15cf99af5de54063fdabfefe66adc075/index.m3u8",
        "mbc 3" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-3-usa/5d58265a862a476dc7f97694addb5ded/index.m3u8",
        "mbc 4" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-4/24f134f1cd63db9346439e96b86ca6ed/index.m3u8",
        "mbc 5" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-5/ee6b000cee0629411b666ab26cb13e9b/index.m3u8",
        "mbc bollywood" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-bollywood/546eb40d7dcf9a209255dd2496903764/index.m3u8",
        "mbc drama" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-drama/2c28a458e2f3253e678b07ac7d13fe71/index.m3u8",
        "mbc fm" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-fm/3f36f7db6086acf058dc51681c87f8ad/index.m3u8",
        "mbc masr" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr/956eac069c78a35d47245db6cdbb1575/index.m3u8",
        "mbc maser" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr/956eac069c78a35d47245db6cdbb1575/index.m3u8",
        "mbc masr 2" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-2/754931856515075b0aabf0e583495c68/index.m3u8",
        "mbc masr drama" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8",
        "mbc maser drama" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8",
        "mbc drama +" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-plus-drama/e37251ec2aac8f6c98f75cd0fa37cd28/index.m3u8",
        "wanasah" to "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-wanasah/13e82ea6232fa647c43b26e8a41f173d/index.m3u8",
    )

    /** Verified public backups for Kids channels whose API entries are
     * dead embeds or stale shahid assets (all verified 200 + multivariant).
     * Keys are normalized channel names. */
    private val kidsFallbacks = mapOf(
        "spacetoon" to "https://live-uae-next.spacetoongo.com/ST_MENA_NEXT/hls/r9p2hjipmw2kl.m3u8",
        "taha kids" to "https://stream.starmenajo.com/hls/app/live/ts:fhd.m3u8",
        "atfal wa mawahib" to "https://5d658d7e9f562.streamlock.net/atfal1.com/atfal2/playlist.m3u8",
    )

    data class EasyBroadcastEvent(
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("stream_no_timeshift") val streamNoTimeshift: String? = null,
    )

    /** SNRT channels point at snrtlive.ma pages (no extractor). Resolve via the
     * EasyBroadcast iframe slug -> player API -> direct m3u8. Returns true if emitted. */
    private suspend fun emitSnrt(
        channelName: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val page = app.get(
                pageUrl,
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "https://snrtlive.ma/"),
                timeout = 15,
            ).text
            val slug = extractEasyBroadcastSlug(page) ?: return false
            val ev = runCatching {
                parseJson<EasyBroadcastEvent>(
                    app.get(
                        "https://snrt.player.easybroadcast.io/api/events/$slug",
                        headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to pageUrl),
                        timeout = 15,
                    ).text
                )
            }.getOrNull() ?: return false
            // DVR playlist keeps timeshift; plain playlist is the live edge.
            val stream = ev.stream?.takeIf { it.isNotBlank() }
                ?: ev.streamNoTimeshift?.takeIf { it.isNotBlank() }
                ?: return false
            if (playEasyBroadcast(stream, "$channelName • SNRT", pageUrl, callback)) {
                return true
            }
            return false
        } catch (_: Exception) { false }
    }

    /** Medi 1 embeds are JWPlayer pages with no static file URL, but the
     * underlying streams live on the same EasyBroadcast CDN (bases below,
     * via iptv-org). Resolved the same way as SNRT. Keys are normalized
     * channel names. */
    private val medi1Streams = mapOf(
        "medi 1 tv maghreb" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-maghreb_jnbspmg/playlist.m3u8",
        "medi 1 maghreb" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-maghreb_jnbspmg/playlist.m3u8",
        "medi 1 tv arabic" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-arabic_g90v4ec/playlist.m3u8",
        "medi 1 arabic" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-arabic_g90v4ec/playlist.m3u8",
        "medi 1 tv afrique" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-afrique_tm7tu45/playlist.m3u8",
        "medi 1 afrique" to "https://cdn.live.easybroadcast.io/abr_corp/83_medi1tv-afrique_tm7tu45/playlist.m3u8",
    )

    private suspend fun emitMedi1(
        channelName: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val base = medi1Streams[normalizeName(channelName)] ?: return false
        return playEasyBroadcast(base, "$channelName • Medi1", pageUrl, callback)
    }

    /** Signs an EasyBroadcast master URL and emits the signed best variant
     * (players drop the ?token query on relative variant URLs, so the
     * master itself is unplayable; .ts segments need no token). */
    private suspend fun playEasyBroadcast(
        baseStreamUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            // The CDN gates playlists (token_authentication): sign the URL
            // first (token endpoint needs no auth). Unsigned -> 403.
            val signedMaster = signEasyBroadcast(baseStreamUrl, referer) ?: return false
            // Players resolve relative variant URLs against the master and
            // drop the ?token query -> 403 on variants. Emit the signed best
            // variant instead; .ts segments play ungated.
            val playable = bestSignedVariant(signedMaster, referer) ?: signedMaster
            callback.invoke(
                newExtractorLink(this.name, label, playable) {
                    this.headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to referer)
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
            true
        } catch (_: Exception) { false }
    }

    /** Signs an EasyBroadcast CDN stream URL via the (unauthenticated)
     * token endpoint. Returns null when signing fails (caller must drop
     * the stream: the CDN answers 403 on unsigned URLs). */
    private suspend fun signEasyBroadcast(streamUrl: String, pageUrl: String): String? {
        return runCatching {
            val q = URLEncoder.encode(streamUrl, "UTF-8")
            val res = app.get(
                "https://token.easybroadcast.io/all?url=$q",
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to pageUrl),
                timeout = 10,
            ).text.trim()
            // "token=...&token_path=...&expires=..."
            if (!res.contains("token=") || !res.contains("expires=")) return@runCatching null
            val sep = if ("?" in streamUrl) "&" else "?"
            "$streamUrl$sep$res"
        }.getOrNull()
    }

    /** Fetches the signed master playlist, picks the highest-bandwidth
     * variant and returns it freshly signed. Null when anything fails
     * (caller falls back to the signed master). */
    private suspend fun bestSignedVariant(signedMasterUrl: String, pageUrl: String): String? {
        return runCatching {
            val master = app.get(
                signedMasterUrl,
                headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to pageUrl),
                timeout = 10,
            ).text
            if ("#EXTM3U" !in master) return@runCatching null
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
            val rel = bestUri ?: return@runCatching null
            val abs = if (rel.startsWith("http")) rel
            else join(signedMasterUrl.substringBeforeLast("/"), rel)
            signEasyBroadcast(abs, pageUrl)
        }.getOrNull()
    }

    /** Emits the best variant of a tokenized master playlist with the
     * original query re-attached (shahid ?t=&e=, ycncdn, boing...).
     * Players drop ?query on relative refs, so emitting the master URL
     * alone yields dead subrequests.
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

    private fun extractEasyBroadcastSlug(page: String): String? {
        // Page HTML varies (escaped slashes, single/double quotes, query strings).
        val clean = page.replace("\\/", "/").replace("\\\"", "\"")
        val patterns = listOf(
            Regex("""snrt\.player\.easybroadcast\.io/events/([A-Za-z0-9_-]+)"""),
            Regex("""easybroadcast[^"'\s<>]*?/events/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""data-event(?:-slug)?=["']([A-Za-z0-9_-]+)["']""", RegexOption.IGNORE_CASE),
        )
        for (rx in patterns) {
            rx.find(clean)?.groupValues?.getOrNull(1)
                ?.trim()?.trimEnd('/', '?', '#')
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
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
            val raw = s.url?.trim()?.takeIf { it.isNotBlank() }?.replace("www.elahmad.coo", "www.elahmad.com")
                ?: return false
            if (!seenUrls.add(raw)) return false
            // SNRT pages need one extra resolution hop (no extractor exists).
            if ("snrtlive.ma" in raw.lowercase()) {
                if (emitSnrt(channelName, raw, callback)) {
                    found = true
                    return true
                }
                // Fall through to the generic extractor attempt below instead
                // of giving up: page markup may change upstream.
            }
            // Medi 1 embeds are dynamic JWPlayer pages: resolve via the
            // EasyBroadcast CDN bases instead of the extractor registry.
            if ("medi1tv.ma" in raw.lowercase()) {
                if (emitMedi1(channelName, raw, callback)) {
                    found = true
                    return true
                }
                // Fall through to the generic extractor attempt below.
            }
            val headers = streamHeaders(s)
            val referer = headers["Referer"]?.takeIf { it.isNotBlank() }
                ?: s.referer?.takeIf { it.isNotBlank() }
                ?: raw
            val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val lower = raw.lowercase()
            // url_type 5/6 (and other non-media pages like mbch.live / arab-stream.live)
            // are web players, not streams: hand them to the extractor registry.
            val isDirect = ".m3u8" in lower || s.urlType == 1 || s.urlType == 3 ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
            if (!isDirect) {
                var resolved = false
                val countCb: (ExtractorLink) -> Unit = { resolved = true; callback(it) }
                runCatching { loadExtractor(raw, referer, subtitleCallback, countCb) }
                if (resolved) found = true
                return resolved
            }
            // Tokenized masters (?t=&e=): players drop the query on relative
            // refs, so resolve the best variant up-front with query kept.
            // A multivariant master with no resolvable variant is unplayable:
            // skip it instead of emitting a link the player errors on.
            if (".m3u8" in lower && "?" in raw) {
                when (emitBestVariant(channelName, serverName, raw, headers, referer, callback)) {
                    1 -> {
                        found = true
                        return true
                    }
                    -1 -> return false
                }
                // Fall through to the raw master URL below (0).
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "$channelName • $serverName",
                    raw,
                ) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: ""
                    this.quality = qualityFor("$channelName $serverName", raw)
                    this.type = if (".m3u8" in raw) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
            return true
        }

        if (info.kind == "event" && info.id != null) {
            val streams = getEventStreams(info.id)
            // Label with the broadcast channel (no match name): "beIN SPORTS 1 • HD".
            val tag = info.channel?.trim()?.takeIf { it.isNotEmpty() }
            streams.forEach { s ->
                val raw = s.url?.trim()?.takeIf { it.isNotBlank() }?.replace("www.elahmad.coo", "www.elahmad.com")
                    ?: return@forEach
                if (!seenUrls.add(raw)) return@forEach
                val headers = streamHeaders(s)
                val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
                val label = if (tag != null && !serverName.equals(tag, ignoreCase = true)) "$tag • $serverName" else serverName
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
                // Same tokenized-master handling as channels (beIN ?t=&e=).
                if (".m3u8" in lower && "?" in raw) {
                    val eventRef = headers["Referer"]?.takeIf { it.isNotBlank() }
                        ?: s.referer?.takeIf { it.isNotBlank() }
                        ?: raw
                    when (emitBestVariant(tag ?: info.name, serverName, raw, headers, eventRef, callback)) {
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

        // Channel (possibly merged beIN ids across qualities).
        val ids = info.ids.ifEmpty { emptyList() }
        if (ids.isEmpty()) return false
        val grouped = coroutineScope {
            ids.map { cid ->
                async { cid to getChannelStreams(cid) }
            }.awaitAll()
        }
        var emitted = false
        grouped.forEach { (_, streams) ->
            streams.forEach { if (emit(info.name, it)) emitted = true }
        }
        // Last resort: verified direct backups when the API has nothing playable.
        if (!emitted) {
            val backup = mbcFallbacks[normalizeName(info.name)]
                ?: kidsFallbacks[normalizeName(info.name)]
            backup?.let { url ->
                if (seenUrls.add(url)) {
                    callback.invoke(
                        newExtractorLink(this.name, "${info.name} • IPTV", url) {
                            this.headers = mapOf("User-Agent" to BROWSER_UA)
                            this.referer = ""
                            this.quality = Qualities.P1080.value
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                    found = true
                }
            }
        }
        return found
    }
}
