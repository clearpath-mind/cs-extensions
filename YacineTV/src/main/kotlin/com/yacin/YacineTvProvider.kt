package com.yacin

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
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class YacineTvProvider : MainAPI() {
    override var mainUrl = "https://def67.ycnapi.com/api"
    // API hosts rotate. The app publishes the current one in Firebase Remote
    // Config ("defaults"); retired hosts answer Cloudflare 403 "Attention
    // Required", which used to leave Today's Matches empty (Oct 2026:
    // def.ycnapi.com retired -> def67.ycnapi.com).

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
        @JsonProperty("competition") val competition: String? = null,
        @JsonProperty("commentary") val commentary: String? = null,
        @JsonProperty("kickoff") val kickoff: Long? = null, // start epoch sec
        @JsonProperty("end") val end: Long? = null, // end epoch sec
        @JsonProperty("team1") val team1: String? = null,
        @JsonProperty("team2") val team2: String? = null,
        @JsonProperty("logo1") val logo1: String? = null,
        @JsonProperty("logo2") val logo2: String? = null,
        @JsonProperty("related") val related: List<LinkData>? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    data class YacineEventResponse(
        @JsonProperty("data") val data: List<YacineEvent>? = null,
    )

    data class YacineEvent(
        @JsonProperty("id") val id: String? = null,
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

    data class YacineCategoryEnvelope(
        @JsonProperty("data") val data: List<YacineCategory>? = null,
        @JsonProperty("vt") val vt: Int? = null,
    )

    data class YacineCategory(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("child_count") val childCount: Int? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    data class YacineChannelResponse(
        @JsonProperty("data") val data: List<YacineChannel>? = null,
    )

    data class YacineChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("priority") val priority: Int? = null,
        @JsonProperty("is_hide") val isHide: Int? = null,
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
        @JsonProperty("drm") val drm: Any? = null,
        @JsonProperty("event_channel_id") val eventChannelId: Int? = null,
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

    // ---- Rotating API host (Firebase Remote Config) -----------------------
    // Public mobile-client identifiers shipped in the Yacine APK
    // (ver3.ycntivi.ofg), not user credentials: they only read the same
    // public Remote Config the Android app uses to rotate its API host.
    private val firebaseProjectNumber = "692330584196"
    private val firebaseApiKey = "AIzaSyDRKL14PPiXzk7qNUNLgV2IsjasxNpWLeU"
    private val firebaseAppId = "1:692330584196:android:68ea9f0c920aa17904cad1"
    private val androidPackage = "ver3.ycntivi.ofg"
    private val hostTtlMs = 6 * 60 * 60_000L
    private val hostRetryMs = 30_000L

    // API roots; the mandatory /api prefix is added in apiHosts()
    // (https://def67.ycnapi.com/events is a 404, /api/events is not).
    private val fallbackHosts = listOf(
        "https://def67.ycnapi.com",
        "https://def.ycnapi.com",
        "https://deft.yacinelive.com",
    )

    // If every direct host fails (WAF/network), each call would pay 3
    // failed requests. Skip them briefly and go straight to the relay.
    @Volatile private var relayUntil = 0L
    private val relayCooldownMs = 10 * 60_000L

    @Volatile private var discoveredHost: String? = null
    @Volatile private var hostCheckedAt: Long = 0L

    private val firebaseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class FirebaseEntries(
        @JsonProperty("entries") val entries: Map<String, String>? = null,
    )

    /** Current API origin from the app's Remote Config, or null on failure. */
    private suspend fun discoverHost(): String? = withContext(Dispatchers.IO) {
        try {
            val payload = """
                {
                  "appInstanceId": "${UUID.randomUUID().toString().replace("-", "")}",
                  "appInstanceIdToken": "",
                  "appId": "$firebaseAppId",
                  "countryCode": "US",
                  "languageCode": "en-US",
                  "platformVersion": "34",
                  "timeZone": "UTC",
                  "appVersion": "3.1",
                  "appBuild": "4",
                  "packageName": "$androidPackage",
                  "sdkVersion": "21.6.2",
                  "analyticsUserProperties": {}
                }
            """.trimIndent()
            val request = Request.Builder()
                .url("https://firebaseremoteconfig.googleapis.com/v1/projects/$firebaseProjectNumber/namespaces/firebase:fetch")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Android-Package", androidPackage)
                .header("X-Goog-Api-Key", firebaseApiKey)
                .header("X-Google-GFE-Can-Retry", "yes")
                .build()
            firebaseClient.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val host = parseJson<FirebaseEntries>(body).entries
                    ?.get("defaults")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val origin = if (host.startsWith("http")) host else "https://$host"
                origin.trimEnd('/')
            }
        } catch (_: Exception) { null }
    }

    /** Remote Config publishes a bare origin ("def67.ycnapi.com"), while
     *  every endpoint lives under /api. Keeping the suffix here (not in
     *  join(), which also resolves page-relative m3u8/iframe URLs) is
     *  what v22 missed: it requested /events and got the origin's 404. */
    private fun apiBase(origin: String): String {
        val base = (if (origin.startsWith("http")) origin else "https://$origin").trimEnd('/')
        return if (base.endsWith("/api")) base else "$base/api"
    }

    /** Hosts to try, current one first. Discovery is cached 6h; a failed
     *  lookup retries shortly instead of pinning fallbacks for hours. */
    private suspend fun apiHosts(): List<String> {
        val now = System.currentTimeMillis()
        val ttl = if (discoveredHost == null) hostRetryMs else hostTtlMs
        if (now - hostCheckedAt >= ttl) {
            hostCheckedAt = now
            discoverHost()?.let { discoveredHost = it }
        }
        return (listOfNotNull(discoveredHost) + fallbackHosts)
            .map { apiBase(it) }
            .distinct()
    }

    /** Only accept decrypted JSON: a Cloudflare HTML challenge (or a
     *  missing/stale `t`) decrypts to noise, and returning that would
     *  stop the host/relay fallback with an empty row. */
    private fun decryptJson(encryptedText: String, tHeader: String): String? {
        val out = decrypt(encryptedText, tHeader).trim()
        return out.takeIf { it.startsWith("{") }
    }

    /** Encrypted API payload, decryptable with the response `t` header.
     *  Direct hosts first (fast, no third party); when this network's
     *  OkHttp traffic is WAF-blocked, the card worker relays the same
     *  body from Cloudflare's egress. */
    private suspend fun getDecrypted(path: String): String? {
        if (System.currentTimeMillis() >= relayUntil) {
            for (host in apiHosts()) {
                try {
                    val res = app.get(
                        join(host, path),
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        timeout = 15,
                    )
                    if (res.code == 200 && res.text.isNotBlank()) {
                        decryptJson(res.text, res.headers["t"] ?: "")?.let { return it }
                    }
                } catch (_: Exception) { /* try next host */ }
            }
            relayUntil = System.currentTimeMillis() + relayCooldownMs
        }
        return getViaRelay(path)
    }

    private suspend fun getViaRelay(path: String): String? {
        return try {
            val res = app.get("$cardBase/yacine/$path", timeout = 20)
            if (res.code == 200 && res.text.isNotBlank()) {
                decryptJson(res.text, res.headers["t"] ?: "")
            } else null
        } catch (_: Exception) { null }
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

    /** Visible channels first: hidden entries (is_hide=1) never
     * resolve, priority desc so main feeds (5) win over XTRA (4)
     * when the card label matches several quality groups. */
    private fun visibleByPriority(channels: List<YacineChannel>): List<YacineChannel> {
        return channels
            .filter { it.isHide != 1 }
            .sortedByDescending { it.priority ?: 0 }
    }

    private suspend fun getChannels(categoryId: String): List<YacineChannel> {
        val json = getDecrypted("categories/$categoryId/channels") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            visibleByPriority(parseJson<YacineChannelResponse>(json).data ?: emptyList())
        }.getOrNull() ?: emptyList()
    }

    @Volatile private var cachedChannelGroups: List<Pair<String, List<YacineChannel>>>? = null
    @Volatile private var cachedChannelGroupsAt: Long = 0L
    private val channelGroupsTtlMs = 10 * 60_000L

    /** Walk the category tree once and merge the four beIN SPORTS quality
     *  tiers into a single "beIN SPORTS" group (dedup by channel name), so
     *  the home page shows one beIN category and one entry per channel. */
    private suspend fun collectChannelGroups(): List<Pair<String, List<YacineChannel>>> {
        val beinMerged = mutableListOf<YacineChannel>()
        val beinSeen = mutableSetOf<String>()
        val others = mutableListOf<Pair<String, List<YacineChannel>>>()

        suspend fun walkBein(cat: YacineCategory) {
            getChannels(cat.id).forEach { c ->
                val key = norm(c.name ?: "")
                if (key.isNotBlank() && beinSeen.add(key)) beinMerged.add(c)
            }
            if (cat.childCount == null || cat.childCount != 0) {
                for (sub in getSubcategories(cat.id)) walkBein(sub)
            }
        }

        suspend fun walk(cat: YacineCategory, parentName: String?) {
            val groupName = if (parentName.isNullOrBlank()) (cat.name ?: "") else "$parentName / ${cat.name}"
            val chs = getChannels(cat.id)
            if (chs.isNotEmpty()) others.add(groupName to chs)
            if (cat.childCount == null || cat.childCount != 0) {
                for (sub in getSubcategories(cat.id)) walk(sub, cat.name)
            }
        }

        for (cat in getCategories()) {
            if ((cat.name ?: "").startsWith("beIN SPORTS")) {
                walkBein(cat)
            } else {
                walk(cat, null)
            }
        }
        val result = mutableListOf<Pair<String, List<YacineChannel>>>()
        if (beinMerged.isNotEmpty()) result.add("beIN SPORTS" to beinMerged)
        result.addAll(others)
        return result
    }

    private suspend fun getAllChannelGroups(): List<Pair<String, List<YacineChannel>>> {
        val now = System.currentTimeMillis()
        cachedChannelGroups?.takeIf { now - cachedChannelGroupsAt < channelGroupsTtlMs }?.let { return it }
        val groups = collectChannelGroups()
        cachedChannelGroups = groups
        cachedChannelGroupsAt = now
        return groups
    }

    private suspend fun getChannelStreams(channelId: String): List<YacineStream> {
        val json = getDecrypted("channel/$channelId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getEventStreams(eventId: String): List<YacineStream> {
        // NOTE: event/{id}/servers now 404s (Oct 2026) — single path.
        val json = getDecrypted("event/$eventId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineStreamResponse>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), " ")

    /** Dedup key for stream URLs: tokenized masters (?t=&e=) refresh
     * every fetch, so exact-string compare treats the same stream as
     * N sources (N previews, slow Binder, ANRs). Strip query/fragment
     * + lowercase scheme/host for the key; full URL still plays. */
    private fun dedupKey(url: String): String {
        val noFrag = url.trim().substringBefore("#")
        val noQuery = noFrag.substringBefore("?")
        val schemeIdx = noQuery.indexOf("://")
        if (schemeIdx < 0) return noQuery.lowercase().trimEnd('/')
        val afterScheme = noQuery.substring(schemeIdx + 3)
        val slashIdx = afterScheme.indexOf("/")
        if (slashIdx < 0) return noQuery.lowercase()
        val hostEnd = schemeIdx + 3 + slashIdx
        return noQuery.substring(0, hostEnd).lowercase() +
            noQuery.substring(hostEnd).trimEnd('/')
    }

    /** Distinguishing URL fragment for colliding source labels: the
     * path segment owning the playlist (ycncdn /live/005/index.m3u8 ->
     * "005", alkass1-p/main.m3u8 -> "alkass1-p"). Pure function so every
     * emit site suffixes repeats identically. */
    private fun urlLabelFragment(url: String): String {
        val segs = url.substringBefore("?").substringBefore("#")
            .substringAfter("://").substringAfter("/").split("/")
            .filter { it.isNotBlank() }
        val last = segs.lastOrNull().orEmpty()
        val frag = if (last.endsWith(".m3u8", ignoreCase = true) ||
            last.endsWith(".mpd", ignoreCase = true)
        ) segs.getOrNull(segs.size - 2) else last
        return frag?.takeIf { it.isNotBlank() }?.take(24) ?: "alt"
    }

    /** Card channel label -> live channel ids (every quality group). The
     * label on the card is what must play; event servers are fallback. */
    private suspend fun resolveChannelIds(label: String): List<String> {
        val want = norm(label)
        if (want.isBlank()) return emptyList()
        fun matchIds(channels: List<YacineChannel>): List<String> {
            val exact = channels
                .filter { norm(it.name ?: "") == want }
                .mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }
                .distinct()
            if (exact.isNotEmpty()) return exact
            // Fuzzy fallback: labels vary ("SSC1" vs "SSC 1",
            // "ON Time Sports 1" vs card "ON Time Sports").
            return channels
                .filter {
                    val n = norm(it.name ?: "")
                    n.isNotBlank() && (n.contains(want) || want.contains(n))
                }
                .mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }
                .distinct()
        }
        runCatching {
            val json = getDecrypted("search?query=${URLEncoder.encode(label.trim(), "UTF-8")}")
                ?: return@runCatching null
            if (json.isBlank()) return@runCatching null
            matchIds(visibleByPriority(parseJson<YacineChannelResponse>(json).data.orEmpty()))
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Full category walk (not just beIN quality groups): ARABIC
        // CHANNELS nests by country (ALGERIA/MOROCCO/...) — /channels on
        // the parent is empty, the country subcategories hold channels.
        // Arryadia TV / ON Time Sports live there, so beIN-only scan
        // could never resolve them.
        return runCatching {
            coroutineScope {
                val top = getCategories()
                val topChannels = top.map { cat -> async { getChannels(cat.id) } }
                val subs = top
                    .filter { it.childCount == null || it.childCount != 0 }
                    .map { cat -> async { getSubcategories(cat.id) } }
                    .awaitAll().flatten()
                val subChannels = subs.map { sub -> async { getChannels(sub.id) } }
                matchIds(
                    (topChannels.awaitAll() + subChannels.awaitAll()).flatten()
                )
            }
        }.getOrNull() ?: emptyList()
    }

    private suspend fun getSubcategories(categoryId: String): List<YacineCategory> {
        val json = getDecrypted("categories/$categoryId") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return runCatching {
            parseJson<YacineCategoryEnvelope>(json).data ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    /** Display title with match info (Cricify structure): "A × B",
     * single name when both sides are identical (a show, not a match),
     * fallback when teams are missing. */
    private fun createDisplayTitle(t1: String, t2: String): String {
        if (t1.isNotBlank() && t2.isNotBlank()) {
            if (t1 == t2) return t1
            return "$t1 × $t2"
        }
        return "مباراة"
    }

    /** Event status from clocks (Cricify logic): ended when past end,
     * live when past start, upcoming when before start. */
    private fun getEventStatus(startSec: Long?, endSec: Long?): String {
        val nowSec = System.currentTimeMillis() / 1000
        return when {
            endSec != null && endSec > 0 && nowSec >= endSec -> "✅"
            startSec != null && startSec > 0 && nowSec >= startSec -> "🔴"
            startSec != null && startSec > 0 && nowSec < startSec -> "🔜"
            else -> ""
        }
    }

    /** yacine-card worker base (Cloudflare Workers, 480x280 Arabic cards). */
    private val cardBase = "https://yacine-card.yacine-card.workers.dev"

    /** Cricify-style generated match card (480x280 PNG): team logos +
     * kickoff + live/ended badge. Arabic team/competition names render via
     * the yacine-card worker (Cairo + resvg shaping). Baked per card. */
    private fun generateCardUrl(
        team1: String?,
        team2: String?,
        title: String?,
        logo1: String?,
        logo2: String?,
        kickoffSec: Long?,
        endSec: Long?,
        channel: String? = null,
        commentary: String? = null,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val time = if (kickoffSec != null && kickoffSec > 0) {
            enc(formatCardTime(kickoffSec))
        } else ""
        val isLive = kickoffSec != null && kickoffSec > 0 && nowSec >= kickoffSec &&
            (endSec == null || endSec <= 0 || nowSec <= endSec)
        val isEnded = endSec != null && endSec > 0 && nowSec > endSec
        return buildString {
            append("$cardBase/?")
            append("title=${enc(title?.takeIf { it.isNotBlank() } ?: "Football")}")
            append("&teamA=${enc(team1.orEmpty())}")
            append("&teamB=${enc(team2.orEmpty())}")
            logo1?.takeIf { it.isNotBlank() }?.let { append("&teamAImg=${enc(it)}") }
            logo2?.takeIf { it.isNotBlank() }?.let { append("&teamBImg=${enc(it)}") }
            if (time.isNotBlank()) append("&time=$time")
            channel?.takeIf { it.isNotBlank() }?.let { append("&channel=${enc(it)}") }
            commentary?.takeIf { it.isNotBlank() }?.let { append("&commentary=${enc(it)}") }
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.isNotBlank() || page > 1) {
            return newHomePageResponse(emptyList(), false)
        }
        val nowSec = System.currentTimeMillis() / 1000
        val links = getEvents()
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
                val displayTitle = createDisplayTitle(t1, t2)
                val status = getEventStatus(
                    e.startTime?.takeIf { it > 0 },
                    e.endTime?.takeIf { it > 0 },
                )
                val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                val poster = if (t1.isNotBlank() && t2.isNotBlank()) {
                    generateCardUrl(
                        t1,
                        t2,
                        e.champions?.trim()?.takeIf { it.isNotBlank() },
                        e.team1?.logo?.takeIf { it.isNotBlank() },
                        e.team2?.logo?.takeIf { it.isNotBlank() },
                        e.startTime?.takeIf { it > 0 },
                        e.endTime?.takeIf { it > 0 },
                        e.channel?.trim()?.takeIf { it.isNotBlank() },
                        e.commentary?.trim()?.takeIf { it.isNotBlank() },
                        nowSec,
                    )
                } else null
                val data = LinkData(
                    eventId = e.id,
                    name = fullTitle,
                    channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                    competition = e.champions?.trim()?.takeIf { it.isNotBlank() },
                    commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                    kickoff = e.startTime?.takeIf { it > 0 },
                    end = e.endTime?.takeIf { it > 0 },
                    team1 = t1.takeIf { it.isNotBlank() },
                    team2 = t2.takeIf { it.isNotBlank() },
                    logo1 = e.team1?.logo?.takeIf { it.isNotBlank() },
                    logo2 = e.team2?.logo?.takeIf { it.isNotBlank() },
                )
                data to poster
            }
        // Recommendations ride along: the other matches (base links, no
        // nesting). Posters regenerate in load() (pure URL building).
        val items = links.map { (data, poster) ->
            val withRelated = data.copy(
                related = links.map { it.first }.filter { it.eventId != data.eventId }.take(12),
            )
            newLiveSearchResponse(withRelated.name, withRelated.toJson(), TvType.Live) {
                this.posterUrl = poster
            }
        }
        val homeLists = mutableListOf<HomePageList>()
        if (items.isNotEmpty()) {
            homeLists.add(HomePageList("Today's Matches", items, isHorizontalImages = true))
        }
        // Browsable beIN SPORTS channels only (one entry per channel name;
        // quality tiers merged, so tapping resolves all qualities). Horizontal
        // thumbnails like Today's Matches.
        runCatching {
            getAllChannelGroups()
                .firstOrNull { it.first == "beIN SPORTS" }
                ?.let { (_, chs) ->
                    val channelItems = chs.mapNotNull { ch ->
                        val nm = ch.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val data = LinkData(kind = "channel", name = nm, logo = ch.logo).toJson()
                        newLiveSearchResponse(nm, data, TvType.Live) {
                            ch.logo?.takeIf { it.isNotBlank() }?.let { this.posterUrl = it }
                        }
                    }
                    if (channelItems.isNotEmpty()) {
                        homeLists.add(HomePageList("beIN SPORTS", channelItems, isHorizontalImages = true))
                    }
                }
        }
        return newHomePageResponse(homeLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val nowSec = System.currentTimeMillis() / 1000
        val results = mutableListOf<SearchResponse>()

        // Events
        results.addAll(
            getEvents()
                .filter { e ->
                    listOfNotNull(
                        e.team1?.name,
                        e.team2?.name,
                        e.champions,
                        e.channel,
                        e.commentary,
                    ).joinToString(" ").contains(q, ignoreCase = true)
                }
                .sortedWith(
                    compareBy(
                        { e ->
                            when {
                                e.startTime != null && e.startTime > 0 && nowSec < e.startTime -> 1
                                e.endTime != null && e.endTime > 0 && nowSec > e.endTime -> 2
                                else -> 0
                            }
                        },
                        { e -> e.startTime ?: Long.MAX_VALUE },
                    )
                )
                .map { e ->
                    val t1 = e.team1?.name?.trim().orEmpty()
                    val t2 = e.team2?.name?.trim().orEmpty()
                    val displayTitle = createDisplayTitle(t1, t2)
                    val status = getEventStatus(
                        e.startTime?.takeIf { it > 0 },
                        e.endTime?.takeIf { it > 0 },
                    )
                    val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                    val poster = if (t1.isNotBlank() && t2.isNotBlank()) {
                        generateCardUrl(
                            t1,
                            t2,
                            e.champions?.trim()?.takeIf { it.isNotBlank() },
                            e.team1?.logo?.takeIf { it.isNotBlank() },
                            e.team2?.logo?.takeIf { it.isNotBlank() },
                            e.startTime?.takeIf { it > 0 },
                            e.endTime?.takeIf { it > 0 },
                            e.channel?.trim()?.takeIf { it.isNotBlank() },
                            e.commentary?.trim()?.takeIf { it.isNotBlank() },
                            nowSec,
                        )
                    } else null
                    val data = LinkData(
                        eventId = e.id,
                        name = fullTitle,
                        channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                        competition = e.champions?.trim()?.takeIf { it.isNotBlank() },
                        commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                        kickoff = e.startTime?.takeIf { it > 0 },
                        end = e.endTime?.takeIf { it > 0 },
                        team1 = t1.takeIf { it.isNotBlank() },
                        team2 = t2.takeIf { it.isNotBlank() },
                        logo1 = e.team1?.logo?.takeIf { it.isNotBlank() },
                        logo2 = e.team2?.logo?.takeIf { it.isNotBlank() },
                    ).toJson()
                    newLiveSearchResponse(fullTitle, data, TvType.Live) {
                        this.posterUrl = poster
                    }
                }
        )

        // Channels: API search first, local fallback.
        val channelCandidates = runCatching {
            val json = getDecrypted("search?query=${URLEncoder.encode(q, "UTF-8")}")
            if (json != null) {
                visibleByPriority(parseJson<YacineChannelResponse>(json).data.orEmpty())
            } else {
                getAllChannelGroups().flatMap { it.second }
                    .filter { (it.name ?: "").contains(q, ignoreCase = true) }
            }
        }.getOrNull() ?: getAllChannelGroups().flatMap { it.second }
            .filter { (it.name ?: "").contains(q, ignoreCase = true) }

        val seenNames = mutableSetOf<String>()
        for (ch in channelCandidates) {
            val nm = ch.name?.trim()?.takeIf { it.isNotBlank() } ?: continue
            if (!seenNames.add(norm(nm))) continue
            val data = LinkData(kind = "channel", name = nm, logo = ch.logo).toJson()
            results.add(
                newLiveSearchResponse(nm, data, TvType.Live) {
                    ch.logo?.takeIf { it.isNotBlank() }?.let { this.posterUrl = it }
                }
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        if (data.kind == "channel") {
            val nm = data.name?.takeIf { it.isNotBlank() } ?: "Channel"
            return newLiveStreamLoadResponse(name = nm, url = url, dataUrl = url) {
                data.logo?.takeIf { it.isNotBlank() }?.let {
                    this.posterUrl = it
                    this.backgroundPosterUrl = it
                }
            }
        }
        // Cricify-style plot (emoji fields) from the baked event data.
        // Lines join with <br><br>: the app renders plot via setTextHtml,
        // which collapses raw newlines.
        val plot = buildString {
            data.competition?.takeIf { it.isNotBlank() }?.let { append("🏆 $it<br><br>") }
            data.kickoff?.let { formatKickoff(it) }?.takeIf { it.isNotBlank() }
                ?.let { append("🕐 $it<br><br>") }
            data.channel?.takeIf { it.isNotBlank() }?.let { append("📺 $it<br><br>") }
            data.commentary?.takeIf { it.isNotBlank() }?.let { append("🎙️ $it") }
        }.trim().takeIf { it.isNotBlank() }
        val banner = if (!data.team1.isNullOrBlank() && !data.team2.isNullOrBlank()) {
            generateCardUrl(data.team1, data.team2, data.competition, data.logo1, data.logo2, data.kickoff, data.end, data.channel, data.commentary)
        } else null
        return newLiveStreamLoadResponse(name = data.name, url = url, dataUrl = url) {
            banner?.let {
                this.posterUrl = it
                this.backgroundPosterUrl = it
            }
            plot?.let { this.plot = it }
            data.related?.takeIf { it.isNotEmpty() }?.let { related ->
                this.recommendations = related.map { rel ->
                    newLiveSearchResponse(rel.name, rel.toJson(), TvType.Live) {
                        this.posterUrl = generateCardUrl(rel.team1, rel.team2, rel.competition, rel.logo1, rel.logo2, rel.kickoff, rel.end, rel.channel, rel.commentary)
                    }
                }
            }
        }
    }

    /** Card kickoff in Arabic (device timezone): "18:00" today
     * (homepage shows today's matches), "غدا 20:00", else
     * "4 أكتوبر، 18:45". Western digits, 24h clock. */
    private fun formatCardTime(epochSec: Long): String {
        return try {
            val tz = java.util.TimeZone.getDefault()
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = tz }
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = tz }
            val at = java.util.Date(epochSec * 1000)
            val time = timeFmt.format(at)
            val kickDate = dateFmt.format(at)
            val nowSec = System.currentTimeMillis() / 1000
            if (kickDate == dateFmt.format(java.util.Date(nowSec * 1000))) return time
            val cal = java.util.Calendar.getInstance(tz).apply {
                timeInMillis = nowSec * 1000
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            if (kickDate == dateFmt.format(cal.time)) return "غدا $time"
            val kc = java.util.Calendar.getInstance(tz).apply { timeInMillis = epochSec * 1000 }
            val months = arrayOf(
                "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
                "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
            )
            "${kc.get(java.util.Calendar.DAY_OF_MONTH)} ${months[kc.get(java.util.Calendar.MONTH)]}، $time"
        } catch (_: Exception) { "" }
    }

    /** Device-local kickoff: "19:45" today, "Tomorrow 20:00" or
     * "22 Sep, 18:45". Blank when already started or unknown. */
    private fun formatKickoff(epochSec: Long?): String {
        if (epochSec == null || epochSec <= 0) return ""
        val nowSec = System.currentTimeMillis() / 1000
        if (epochSec <= nowSec) return ""
        return try {
            val tz = java.util.TimeZone.getDefault()
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = tz }
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = tz }
            val time = timeFmt.format(java.util.Date(epochSec * 1000))
            val kickDate = dateFmt.format(java.util.Date(epochSec * 1000))
            val nowDate = dateFmt.format(java.util.Date(nowSec * 1000))
            if (kickDate == nowDate) {
                time
            } else {
                val cal = java.util.Calendar.getInstance(tz).apply {
                    timeInMillis = nowSec * 1000
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                if (kickDate == dateFmt.format(cal.time)) "Tomorrow $time"
                else java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US).apply { timeZone = tz }
                    .format(java.util.Date(epochSec * 1000))
            }
        } catch (_: Exception) { "" }
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
            label.equals("Low", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun streamHeaders(s: YacineStream): Map<String, String> {
        // Working API entries (beIN, Alkass channel) all carry this
        // browser UA; the event "Local" entry carries none. Default to
        // it instead of okhttp: GCS-fronted hosts gate on UA, and the
        // player (Cronet) sends a browser UA anyway.
        val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
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
            ?: browserUa
        (s.referer?.takeIf { it.isNotBlank() } ?: out["Referer"])
            ?.takeIf { it.isNotBlank() }?.let { out["Referer"] = it }
        return out
    }

    /** Plain-HTTP m3u8 sniff for url_type=5 embed pages (no extractor
     * handles them): arab-stream.live embeds a brightcove playlist,
     * snrtlive.ma embeds an easybroadcast iframe. Fast pass before
     * loadExtractor / WebView. */
    private val pageM3u8Regex = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
    private val pageIframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private suspend fun sniffM3u8FromPage(
        channelName: String,
        serverName: String,
        pageUrl: String,
        headers: Map<String, String>,
        referer: String,
        uniqueLabel: (String, String) -> String,
        callback: (ExtractorLink) -> Unit,
    ): Pair<Boolean, String?> {
        return try {
            var found = false
            suspend fun collect(html: String, base: String) {
                pageM3u8Regex.findAll(html).map { it.value }.distinct().forEach { u ->
                    callback.invoke(
                        newExtractorLink(this.name, uniqueLabel("$channelName • $serverName", u), u) {
                            this.headers = headers
                            this.referer = base
                            this.quality = qualityFor("$channelName $serverName", u)
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                    found = true
                }
            }
            fun absolutize(src: String): String = when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "https://" + pageUrl.substringAfter("://").substringBefore("/") + src
                else -> join(pageUrl.substringBeforeLast("/"), src)
            }
            val doc = app.get(pageUrl, headers = headers, timeout = 12).text
            collect(doc, pageUrl)
            // First iframe (snrtlive.ma -> easybroadcast player): the
            // WebView stage loads it directly, skipping the wrapper.
            val iframe = pageIframeRegex.findAll(doc)
                .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { src -> src.isNotBlank() } }
                .distinct().firstOrNull()?.let(::absolutize)
            if (!found && iframe != null) {
                runCatching {
                    collect(app.get(iframe, headers = headers, timeout = 12).text, iframe)
                }
            }
            found to iframe
        } catch (_: Exception) { false to null }
    }

    /** Last resort for embed pages: real device WebView sniff for .m3u8
     * subrequests. JS players (easybroadcast) only fetch the stream
     * after play is pressed, so an autoplay script runs on every
     * subrequest and the video element's resolved src is harvested via
     * scriptCallback as well. resolveUsingWebView (not the interceptor
     * form) so additional matches are collected too. */
    private suspend fun sniffM3u8ViaWebView(
        channelName: String,
        serverName: String,
        pageUrl: String,
        headers: Map<String, String>,
        referer: String,
        uniqueLabel: (String, String) -> String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val scriptSrc = java.util.concurrent.atomic.AtomicReference<String?>(null)
            suspend fun emitUrl(u: String, base: String): Boolean {
                if (".m3u8" !in u || !u.startsWith("http")) return false
                callback.invoke(
                    newExtractorLink(this.name, uniqueLabel("$channelName • $serverName", u), u) {
                        this.headers = headers
                        this.referer = base
                        this.quality = qualityFor("$channelName $serverName", u)
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                return true
            }
            val autoplayJs = "(function(){try{" +
                "var v=document.querySelector('video');if(v){v.muted=true;try{" +
                "var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}}" +
                "var b=document.querySelector('button[class*=play],div[class*=play],[class*=vjs-big-play]');" +
                "if(b&&b.click)try{b.click()}catch(e){}" +
                "return (v&&(v.currentSrc||v.src))||'';}catch(e){return '';}})()"
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8(\?.*)?"""),
                additionalUrls = listOf(
                    Regex("""\.m3u8(\?.*)?"""),
                    Regex("""\.mpd(\?.*)?"""),
                ),
                useOkhttp = false,
                script = autoplayJs,
                scriptCallback = { result ->
                    // Plain callback (no suspend): just record, emit below.
                    val u = result.trim().trim('"').replace("\\/", "/")
                    if (".m3u8" in u && u.startsWith("http")) {
                        scriptSrc.compareAndSet(null, u)
                    }
                },
                timeout = 30_000L,
            )
            val (fixed, extra) = resolver.resolveUsingWebView(pageUrl, referer, headers)
            var found = false
            // Dedupe: video-element src, intercepted request, extras.
            (listOfNotNull(scriptSrc.get()) +
                listOf(fixed?.url?.toString().orEmpty()) +
                extra.map { it.url.toString() })
                .distinct()
                .filter { it.isNotBlank() && it != pageUrl }
                .forEach { u ->
                    if (emitUrl(u, referer)) found = true
                }
            found
        } catch (_: Exception) { false }
    }

    /** Liveness probe for direct m3u8 candidates (ranged GET, playlists
     * are small): true = alive, false = definitively dead (4xx),
     * null = unknown (timeout/5xx → fail-open, keep order). The player
     * auto-plays the first link, so dead-first ordering (event Multi
     * 403 while the channel stream works) reads as "not working". */
    private suspend fun probePlaylist(url: String, headers: Map<String, String>): Boolean? {
        return try {
            val res = app.get(
                url,
                headers = headers + ("Range" to "bytes=0-4095"),
                timeout = 6,
            )
            when {
                res.code in 200..299 &&
                    ("#EXTM3U" in res.text ||
                        "mpegurl" in (res.headers["Content-Type"] ?: "") ||
                        "mpegurl" in (res.headers["content-type"] ?: "")) -> true
                res.code in 400..499 -> false
                else -> null
            }
        } catch (_: Exception) { null }
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
        uniqueLabel: (String, String) -> String,
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
                newExtractorLink(this.name, uniqueLabel("$channelName • $serverName", variantUrl), variantUrl) {
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
        val seenEmitted = mutableSetOf<String>()
        val usedLabels = mutableSetOf<String>()

        /** First emission keeps the plain "$channel • $server" label;
         * repeats with different URLs (backend names every quality
         * group "1") get a URL-fragment suffix: "ENTV • 1 (005)". */
        fun uniqueLabel(base: String, url: String): String {
            if (usedLabels.add(base)) return base
            var candidate = "$base (${urlLabelFragment(url)})"
            var i = 2
            while (!usedLabels.add(candidate)) {
                candidate = "$base (${urlLabelFragment(url)} $i)"
                i++
            }
            return candidate
        }

        /** Single choke point for every emitted link: token refreshes
         * (?t=&e=) and event-vs-channel copies collapse to one source,
         * so the player only generates one preview per stream. */
        fun tryEmit(link: ExtractorLink): Boolean {
            if (!seenEmitted.add(dedupKey(link.url))) return false
            callback(link)
            found = true
            return true
        }

        suspend fun emit(channelName: String, s: YacineStream): Boolean {
            val raw = s.url?.trim()?.takeIf { it.isNotBlank() } ?: return false
            if (!seenUrls.add(dedupKey(raw))) return false
            val headers = streamHeaders(s)
            val referer = headers["Referer"]?.takeIf { it.isNotBlank() }
                ?: s.referer?.takeIf { it.isNotBlank() }
                ?: raw
            val serverName = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            val lower = raw.lowercase()
            val isDirect = ".m3u8" in lower || s.urlType == 1 || s.urlType == 3 ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
            if (!isDirect) {
                // Embed pages (url_type=5): arab-stream.live, snrtlive.ma,
                // ... No stock extractor handles them, so sniff the page
                // for an m3u8 first, then loadExtractor, then WebView
                // (which loads the inner iframe directly + autoplays).
                val (pageFound, iframe) = sniffM3u8FromPage(channelName, serverName, raw, headers, referer, ::uniqueLabel, { tryEmit(it) })
                if (pageFound) {
                    return true
                }
                var resolved = false
                runCatching {
                    loadExtractor(raw, referer, subtitleCallback) { if (tryEmit(it)) resolved = true }
                }
                if (resolved) {
                    return true
                }
                if (sniffM3u8ViaWebView(channelName, serverName, iframe ?: raw, headers, referer, ::uniqueLabel, { tryEmit(it) })) {
                    return true
                }
                return false
            }
            if (".m3u8" in lower && "?" in raw) {
                if (emitBestVariant(channelName, serverName, raw, headers, referer, ::uniqueLabel, { tryEmit(it) })) {
                    return true
                }
            }
            return tryEmit(
                newExtractorLink(this.name, uniqueLabel("$channelName • $serverName", raw), raw) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: ""
                    this.quality = qualityFor("$channelName $serverName", raw)
                    this.type = if (".m3u8" in raw) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }

        // Event servers first: labeled per match (HD/SD/Low). The channel
        // endpoints all return name "1" for every quality group, so
        // channel-first produced "beIN SPORTS 4 • 1" xN with no quality.
        // No early return: event links are sometimes dead (403) while
        // the channel fallback holds a working stream, so both sources
        // are always aggregated. Direct m3u8 candidates are
        // liveness-probed concurrently and definitively-dead ones (HTTP
        // 4xx) are skipped: the player auto-plays the first link, so a
        // dead-first emission reads as "not working". Unknown
        // (timeout/5xx) stays fail-open.
        val tag = info.channel?.trim()?.takeIf { it.isNotEmpty() }
        val eid = info.eventId?.takeIf { it.isNotBlank() }
        val candidates = mutableListOf<Pair<String, YacineStream>>()
        val isChannel = info.kind == "channel"
        if (isChannel) {
            val label = (info.name ?: info.channel)?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null) {
                val ids = resolveChannelIds(label)
                if (ids.isNotEmpty()) {
                    coroutineScope {
                        ids.map { cid -> async { getChannelStreams(cid) } }
                            .awaitAll().flatten()
                            .forEach { candidates.add(label to it) }
                    }
                }
            }
        } else {
            if (eid != null) {
                getEventStreams(eid).forEach { candidates.add((tag ?: info.name) to it) }
            }
            // Fallback: the card's channel label across quality groups.
            if (tag != null) {
                val ids = resolveChannelIds(tag)
                if (ids.isNotEmpty()) {
                    coroutineScope {
                        ids.map { cid -> async { getChannelStreams(cid) } }
                            .awaitAll().flatten()
                            .forEach { candidates.add(tag to it) }
                    }
                }
            }
        }
        val distinct = candidates.filter { (_, s) ->
            !s.url?.trim().isNullOrBlank()
        }.distinctBy { (_, s) -> dedupKey(s.url!!.trim()) }
        val (m3u8s, rest) = distinct.partition { (_, s) ->
            ".m3u8" in s.url!!.lowercase()
        }
        val probe = coroutineScope {
            m3u8s.map { c ->
                async {
                    val headers = streamHeaders(c.second)
                    c to probePlaylist(c.second.url!!.trim(), headers)
                }
            }.awaitAll()
        }
        // Stable: alive + unknown keep original relative order, dead
        // (HTTP 4xx, e.g. Alkass GCS 403) are skipped, not emitted: the
        // player auto-plays the first link, so emitting a known-dead
        // link spins Cronet into a 403 (ANRs in logcat) before the same
        // "No Links Found" toast. Unknown (timeout/5xx) stays fail-open.
        val orderedDirects = probe.filter { (_, alive) -> alive != false }
            .sortedBy { (c, _) -> m3u8s.indexOf(c) }
            .map { it.first }
        (orderedDirects + rest).forEach { (label, s) ->
            if (emit(label, s)) found = true
        }
        return found
    }
}
