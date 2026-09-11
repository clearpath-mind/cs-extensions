package com.yacin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    /** On-device match banner cache (event id -> cached PNG path). */
    private val bannerCache = ConcurrentHashMap<Long, String>()

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
        for (base in listOf(mainUrl, fallbackUrl)) {
            try {
                val res = app.get(
                    join(base, path),
                    headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                    timeout = 10,
                )
                if (res.code == 200 && res.text.isNotBlank()) {
                    val t = res.headers["t"] ?: ""
                    return decrypt(res.text, t) to t
                }
            } catch (_: Exception) { continue }
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

    private fun cleanCategoryName(name: String?): String {
        val n = (name ?: "أخرى").trim()
        if (beinQualityRegex.containsMatchIn(n)) return "beIN SPORTS"
        return n
    }

    private fun formatKickoff(epochSec: Long?): String {
        if (epochSec == null || epochSec <= 0) return ""
        return try {
            // Latin digits (normal numbers), not Eastern Arabic numerals.
            val fmt = SimpleDateFormat("dd/MM - HH:mm", Locale.US)
            fmt.format(Date(epochSec * 1000))
        } catch (_: Exception) { "" }
    }

    private fun eventTitle(e: YacineEvent): String {
        val t1 = e.team1?.name?.trim().orEmpty()
        val t2 = e.team2?.name?.trim().orEmpty()
        if (t1.isNotBlank() && t2.isNotBlank()) return "$t1 × $t2"
        return e.champions?.trim().orEmpty().ifBlank { "مباراة" }
    }

    private fun matchPlot(title: String): String {
        return "شاهد البث المباشر لمباراة $title"
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

            // 1) Matches first (horizontal cards with composite banners).
            val events = eventsDeferred.await()
            if (events.isNotEmpty()) {
                // Banners render in parallel; each is guarded so one slow
                // logo download never blocks the homepage.
                val banners = events.map { e ->
                    async { withTimeoutOrNull(12_000) { matchBanner(e) } }
                }.awaitAll()
                val matchLinks = events.zip(banners).mapNotNull { (e, banner) ->
                    val id = e.id ?: return@mapNotNull null
                    val title = eventTitle(e)
                    // Composite banner when renderable, else team1 logo with
                    // team2 as fallback. Detail page shows both (poster + background).
                    val poster = banner
                        ?: e.team1?.logo?.takeIf { it.isNotBlank() }
                        ?: e.team2?.logo?.takeIf { it.isNotBlank() }
                    val poster2 = e.team2?.logo?.takeIf { it.isNotBlank() }
                        ?: e.team1?.logo?.takeIf { it.isNotBlank() }
                    LinkData(
                        kind = "event",
                        id = id,
                        name = title,
                        poster = poster,
                        poster2 = poster2,
                        channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                        competition = e.champions?.trim()?.takeIf { it.isNotBlank() },
                        commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                        kickoff = formatKickoff(e.startTime).takeIf { it.isNotBlank() },
                        plot = matchPlot(title),
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
            // Today's Matches, beIN SPORTS, Morocco, MBC CHANNELS, KIDS CHANNELS.
            val wantedTopRows = setOf("mbc channels", "kids channels")
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
                    listOfNotNull(channelRow("Morocco", subChannels, moroccoThumbs))
                }
            }.awaitAll().flatten()

            lists.addAll(otherRows)
            lists
        }
    }

    /** 1280x720 composite match banner (competition badge + name, VS,
     * both crests on a dark gradient), rendered on-device from the API
     * team logos and cached under cacheDir/yacine_banners. Returns the
     * cached PNG path, or null when rendering is impossible (caller falls
     * back to the API logo). */
    private suspend fun matchBanner(e: YacineEvent): String? {
        val id = e.id ?: return null
        bannerCache[id]?.let { if (File(it).exists()) return it }
        val l1 = e.team1?.logo?.takeIf { it.isNotBlank() }
        val l2 = e.team2?.logo?.takeIf { it.isNotBlank() }
        if (l1.isNullOrBlank() && l2.isNullOrBlank()) return null
        val ctx = appContext ?: return null
        // v2 layout (VS + competition): new filename so stale v1 files regenerate.
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "yacine_banners").apply { mkdirs() }
                val out = File(dir, "match_${id}_v2.png")
                if (out.exists() && out.length() > 0) {
                    bannerCache[id] = out.absolutePath
                    return@runCatching out.absolutePath
                }
                val b1 = l1?.let { downloadBitmap(it) }
                val b2 = l2?.let { downloadBitmap(it) }
                if (b1 == null && b2 == null) return@runCatching null
                val compName = e.champions?.trim()?.takeIf { it.isNotBlank() }
                val bmp = renderBanner(b1, b2, compName)
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bmp.recycle()
                if (b1 != null && b1 != bmp) b1.recycle()
                if (b2 != null && b2 != bmp) b2.recycle()
                bannerCache[id] = out.absolutePath
                out.absolutePath
            }.getOrNull()
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun renderBanner(left: Bitmap?, right: Bitmap?, compName: String?): Bitmap {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val grad = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#3A0D0D"), Color.parseColor("#0D0303"),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = grad })
        // Soft center glow, like the reference banners.
        c.drawCircle(
            w / 2f, h / 2f, 150f,
            Paint().apply { color = Color.argb(28, 255, 200, 200); isAntiAlias = true },
        )
        fun drawCrest(b: Bitmap?, cx: Float) {
            if (b == null) return
            val maxSide = 380
            val scale = minOf(
                maxSide / b.width.toFloat(),
                maxSide / b.height.toFloat(),
                3.5f,
            )
            val dw = (b.width * scale).toInt().coerceAtLeast(1)
            val dh = (b.height * scale).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(b, dw, dh, true)
            c.drawBitmap(
                s, cx - dw / 2f, h / 2f - dh / 2f,
                Paint().apply { isFilterBitmap = true; isAntiAlias = true },
            )
            if (s != b) s.recycle()
        }
        drawCrest(left, w * 0.22f)
        drawCrest(right, w * 0.78f)
        val textPaint = Paint().apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        // Competition name top-center (Canvas shapes Arabic correctly).
        compName?.let {
            c.drawText(it, w / 2f, 130f, textPaint.apply { textSize = 40f })
        }
        // VS in the middle, with shadow for contrast.
        c.drawText(
            "VS", w / 2f, h / 2f + 44f,
            textPaint.apply {
                textSize = 124f
                isFakeBoldText = true
                setShadowLayer(12f, 0f, 4f, Color.argb(160, 0, 0, 0))
            },
        )
        return bmp
    }

    /** Official snrtlive.ma vignette arts for the SNRT channels
     * (verified 200; ~5-7 KB each). Other Morocco entries (2M, Medi 1,
     * Télé Maroc) keep API logos. Keys are normalized channel names. */
    private val moroccoThumbs = mapOf(
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
            val eventsDeferred = async { getEvents() }
            val categories = getCategories()
            val q = query.trim()

            // Search mirrors the curated homepage: beIN qualities, Morocco,
            // MBC CHANNELS, KIDS CHANNELS (+ events below).
            val wantedTopRows = setOf("mbc channels", "kids channels")
            val channelDeferred = categories.mapNotNull { cat ->
                if (isBeinQuality(cat)) return@mapNotNull cat to false
                val norm = normalizeName(cat.name ?: "")
                when {
                    norm == "arabic channels" || cat.id == 9 -> cat to true // parent
                    wantedTopRows.contains(norm) -> cat to false
                    else -> null
                }
            }.map { (cat, isParent) ->
                async {
                    if (!isParent) return@async getChannels(cat.id)
                    val subs = getSubcategories(cat.id)
                    val morocco = subs.firstOrNull {
                        it.id == 15 || normalizeName(it.name ?: "") == "morocco"
                    } ?: return@async emptyList()
                    getChannels(morocco.id)
                }
            }.awaitAll().flatten()

            val out = mutableListOf<SearchResponse>()
            // Merge hits by channel name so beIN search results keep all quality ids.
            val mergedHits = linkedMapOf<String, SearchHit>()
            channelDeferred.forEach { ch ->
                val nm = ch.name?.trim() ?: return@forEach
                if (!nm.contains(q, ignoreCase = true)) return@forEach
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

            eventsDeferred.await().forEach { e ->
                val title = eventTitle(e)
                val hay = listOfNotNull(title, e.champions, e.channel, e.team1?.name, e.team2?.name)
                    .joinToString(" ")
                if (!hay.contains(q, ignoreCase = true)) return@forEach
                val id = e.id ?: return@forEach
                val poster = e.team1?.logo?.takeIf { it.isNotBlank() }
                    ?: e.team2?.logo?.takeIf { it.isNotBlank() }
                val poster2 = e.team2?.logo?.takeIf { it.isNotBlank() }
                    ?: e.team1?.logo?.takeIf { it.isNotBlank() }
                val data = LinkData(
                    kind = "event",
                    id = id,
                    name = title,
                    poster = poster,
                    poster2 = poster2,
                    channel = e.channel?.trim()?.takeIf { it.isNotBlank() },
                    competition = e.champions?.trim()?.takeIf { it.isNotBlank() },
                    commentary = e.commentary?.trim()?.takeIf { it.isNotBlank() },
                    kickoff = formatKickoff(e.startTime).takeIf { it.isNotBlank() },
                    plot = matchPlot(title),
                ).toJson()
                out.add(
                    newLiveSearchResponse(title, data, TvType.Live) {
                        this.posterUrl = poster
                    }
                )
            }
            out
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        val plot = data.plot
            ?: if (data.kind == "event") matchPlot(data.name)
            else "شاهد البث المباشر لقناة ${data.name}"
        return newMovieLoadResponse(data.name, url, TvType.Live, url) {
            this.posterUrl = data.poster
            // Matches: hero shows the same homepage thumbnail (banner);
            // fall back to the other team logo when no banner was rendered.
            if (data.kind == "event") {
                this.backgroundPosterUrl = data.poster ?: data.poster2
            }
            this.plot = plot
            // Match meta as tags, in order: competition, kickoff,
            // commentator, broadcast channel.
            if (data.kind == "event") {
                val tags = listOfNotNull(
                    data.competition?.takeIf { it.isNotBlank() },
                    data.kickoff?.takeIf { it.isNotBlank() },
                    data.commentary?.takeIf { it.isNotBlank() },
                    data.channel?.takeIf { it.isNotBlank() },
                )
                if (tags.isNotEmpty()) this.tags = tags
            }
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
        // Last resort: verified iptv-org direct stream when the API has nothing playable.
        if (!emitted) {
            mbcFallbacks[normalizeName(info.name)]?.let { url ->
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
