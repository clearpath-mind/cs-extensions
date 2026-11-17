package com.freesideplus

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class WpRendered(
    @JsonProperty("rendered") val rendered: String? = null,
)

data class WpPost(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("title") val title: WpRendered? = null,
    @JsonProperty("content") val content: WpRendered? = null,
    @JsonProperty("featured_media") val featuredMedia: Int = 0,
    @JsonProperty("categories") val categories: List<Int>? = null,
)

data class WpMediaSize(
    @JsonProperty("source_url") val sourceUrl: String? = null,
)

data class WpMediaDetails(
    @JsonProperty("sizes") val sizes: Map<String, WpMediaSize>? = null,
)

data class WpMedia(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("source_url") val sourceUrl: String? = null,
    @JsonProperty("media_details") val mediaDetails: WpMediaDetails? = null,
)

data class WpCategory(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
)

data class LinkData(
    @JsonProperty("id") val id: Int = 0,
)

data class FspServer(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("download") val download: String? = null,
)

data class FspSeries(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
)

/** Payload of the theme's `fsp/v1/episode/{id}` route, the same one fx.js uses. */
data class FspEpisode(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("servers") val servers: List<FspServer>? = null,
    @JsonProperty("desc") val desc: String? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("num") val num: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("thumb_lg") val thumbLg: String? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("series") val series: FspSeries? = null,
)

class FreeSidePlusProvider : MainAPI() {
    override var mainUrl = "https://freesideplus.plus"
    private val apiBase = "$mainUrl/wp-json/wp/v2"
    override var name = "FreeSidePlus"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    override val getMainPageTimeoutMs = 60_000L

    // Editorial order: Latest, then the main shows.
    override val mainPage = mainPageOf(
        "0" to "Latest Episodes",
        "37" to "Side+ Saturdays",
        "34" to "Sidecast",
        "36" to "BTS",
    )

    // Site art is portrait (1280x1920, 2:3); these categories render vertical cards.
    private val verticalCategories = setOf(37) // Side+ Saturdays

    // Full fields (with content HTML) — only for single-post load()/loadLinks().
    private val postFields = "_fields=id,link,date,title,content,categories,featured_media"
    // List fields (no content): the full post HTML is ~50x the payload
    // (122KB/12.8s vs 2.4KB/3.2s for 12 posts) and cards never use it.
    private val listFields = "_fields=id,link,date,title,categories,featured_media"
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val durationRegex = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")
    private val qualitySuffixRegex = Regex("""\s*\(?\d{3,4}p\)?\s*$""", RegexOption.IGNORE_CASE)

    private fun unescape(s: String): String = Parser.unescapeEntities(s, false).trim()

    // Media IDs repeat across rows and paginations — cache hits skip the
    // /media round-trip entirely (server takes ~2-8s per call). The key carries
    // orientation too: the same post appears in horizontal rows (Latest) and in
    // the vertical Side+ Saturdays row, which need different crops.
    private val posterCache = ConcurrentHashMap<String, String>()

    private fun posterKey(id: Int, vertical: Boolean): String = "$id:${if (vertical) "v" else "h"}"

    private data class CachedRow(
        val timestamp: Long,
        val items: List<SearchResponse>,
        val hasNext: Boolean,
    )
    // Homepage has no app-level cache; a short TTL makes back-nav/initial
    // revisit instant without noticeably delaying new episodes.
    private val rowCache = ConcurrentHashMap<String, CachedRow>()
    private val rowTtlMs = 3 * 60 * 1000L

    private data class CachedEpisode(
        val timestamp: Long,
        val episode: FspEpisode,
    )
    // load() and loadLinks() want the same payload; caching it makes opening an
    // episode cost one request instead of two.
    private val episodeCache = ConcurrentHashMap<Int, CachedEpisode>()
    private val episodeTtlMs = 10 * 60 * 1000L

    /** Everything load()/loadLinks() need, straight from the theme's episode route. */
    private suspend fun fetchEpisode(id: Int): FspEpisode? {
        if (id <= 0) return null
        episodeCache[id]?.takeIf { System.currentTimeMillis() - it.timestamp < episodeTtlMs }
            ?.let { return it.episode }
        val episode = runCatching {
            parseJson<FspEpisode>(app.get("$mainUrl/wp-json/fsp/v1/episode/$id").text)
                .takeIf { it.title?.isNotBlank() == true }
        }.getOrNull() ?: return null
        episodeCache[id] = CachedEpisode(System.currentTimeMillis(), episode)
        return episode
    }

    /** Some server rows ship broken ("https:// bysevepoin.com/…"); drop the gap. */
    private fun normalizeEmbed(url: String?): String? =
        url?.trim()
            ?.replace(Regex("""^(https?://)\s+""")) { it.groupValues[1] }
            ?.takeIf { it.startsWith("http") }

    /** "Mirror" rows that are not videos: Side+ landing pages and raw image assets. */
    private fun isJunkServer(url: String): Boolean =
        url.contains("sideplus.com", true) || url.contains("imgix.net", true)

    /** The site occasionally puts unrelated copy in the description field. */
    private val junkPlots = listOf(
        "You will immediately log out of this account",
        "Click here to apply",
    )

    private fun plotText(raw: String?): String? =
        raw?.trim()?.takeIf { plot ->
            plot.isNotBlank() && junkPlots.none { plot.contains(it, ignoreCase = true) }
        }

    /** "November 5, 2026" -> "5 Nov 2026". */
    private fun formatApiDate(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val parsed = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US)
                .parse(raw) ?: return null
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(parsed)
        } catch (_: Exception) { null }
    }

    /** "BTS [2026]" -> "2026"; null when the season just repeats the series name. */
    private fun seasonLabel(season: String?, seriesName: String?): String? {
        var label = season?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val name = seriesName?.trim().orEmpty()
        if (name.isNotEmpty() && label.startsWith(name, ignoreCase = true)) {
            label = label.substring(name.length)
        }
        label = label.trim(' ', '-', '–', '—', ':', '[', ']', '(', ')')
        return label.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
    }

    /** "#264 – We End On GTA"; titles already carrying "Episode N" keep their form. */
    private fun episodeTitle(episode: FspEpisode): String? {
        val title = episode.title?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: return null
        val num = episode.num?.trim()?.takeIf { it.isNotEmpty() } ?: return title
        val numbered = Regex("""\b(?:ep|episode)\.?\s*$num\b""", RegexOption.IGNORE_CASE)
        return if (numbered.containsMatchIn(title)) title else "#$num – $title"
    }

    /** "00:25:01" / "25:01" -> minutes, rounded up like the site's own runtime. */
    private fun runtimeMinutes(value: String?): Int? {
        val match = value?.let { durationRegex.find(it) } ?: return null
        val parts = match.groupValues
        val totalSeconds = if (parts[3].isNotEmpty()) {
            (parts[1].toIntOrNull() ?: 0) * 3600 +
                (parts[2].toIntOrNull() ?: 0) * 60 +
                (parts[3].toIntOrNull() ?: 0)
        } else {
            (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
        }
        return (totalSeconds + 30) / 60
    }

    private var categoryNames: Map<Int, String>? = null

    private suspend fun getCategoryNames(): Map<Int, String> {
        categoryNames?.let { return it }
        val map = runCatching {
            parseJson<List<WpCategory>>(
                app.get("$apiBase/categories?per_page=100&_fields=id,name").text
            ).mapNotNull { cat ->
                val n = cat.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                cat.id to Parser.unescapeEntities(n, false)
            }.toMap()
        }.getOrNull().orEmpty()
        categoryNames = map
        return map
    }

    /** "2026-09-25T20:37:01" -> "25 Sep 2026". */
    private fun formatDate(iso: String): String? {
        return try {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(iso.substring(0, 10)) ?: return null
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(parsed)
        } catch (_: Exception) { null }
    }

    /** Drops trailing quality tokens sites bake into titles ("… (1080p)"). */
    private fun cleanTitle(raw: String): String =
        unescape(raw).replace(qualitySuffixRegex, "").trim()

    /**
     * Poster URL for a featured image. Site originals are portrait (1280x1920),
     * so horizontal cards use the theme's landscape crops while vertical cards
     * use the 2:3 sizes.
     */
    private fun posterUrl(media: WpMedia, vertical: Boolean = false): String? {
        val sizes = media.mediaDetails?.sizes
        val preferred = if (vertical) {
            listOf("medium_large", "large", "1536x1536")
        } else {
            listOf("jannah-image-post", "jannah-image-large")
        }
        preferred.firstNotNullOfOrNull { name ->
            sizes?.get(name)?.sourceUrl?.takeIf { it.startsWith("http") }
        }?.let { return it }
        return media.sourceUrl?.takeIf { it.startsWith("http") }
    }

    private suspend fun fetchPosters(ids: List<Int>, vertical: Boolean = false): Map<Int, String> {
        val wanted = ids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val cached = wanted.mapNotNull { id ->
            posterCache[posterKey(id, vertical)]?.let { id to it }
        }.toMap()
        val missing = wanted.filter { posterCache[posterKey(it, vertical)] == null }
        if (missing.isEmpty()) return cached
        val fetched = runCatching {
            val url = "$apiBase/media?include=${missing.joinToString(",")}" +
                "&per_page=100&_fields=id,source_url,media_details"
            parseJson<List<WpMedia>>(app.get(url).text).mapNotNull { media ->
                val thumb = posterUrl(media, vertical) ?: return@mapNotNull null
                media.id to thumb
            }.toMap()
        }.getOrNull().orEmpty()
        fetched.forEach { (id, url) -> posterCache[posterKey(id, vertical)] = url }
        return cached + fetched
    }

    private fun toSearchResponse(post: WpPost, posters: Map<Int, String>): SearchResponse? {
        if (post.id <= 0) return null
        val title = post.title?.rendered?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, LinkData(post.id).toJson(), TvType.Movie) {
            this.posterUrl = posters[post.featuredMedia]
        }
    }

    private suspend fun fetchPosts(url: String): Pair<List<WpPost>, Boolean> {
        val res = app.get(url)
        val posts = runCatching { parseJson<List<WpPost>>(res.text) }.getOrNull().orEmpty()
        val totalPages = (0 until res.headers.size)
            .firstOrNull { res.headers.name(it).equals("X-WP-TotalPages", ignoreCase = true) }
            ?.let { res.headers.value(it).toIntOrNull() }
        val page = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        return posts to (totalPages == null || page < totalPages)
    }

    private suspend fun postsToCards(
        posts: List<WpPost>,
        vertical: Boolean = false,
    ): List<SearchResponse> {
        if (posts.isEmpty()) return emptyList()
        val posters = fetchPosters(posts.map { it.featuredMedia }, vertical)
        return posts.mapNotNull { toSearchResponse(it, posters) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catId = request.data.toIntOrNull() ?: 0
        val vertical = catId in verticalCategories
        val key = "$catId:$page"
        rowCache[key]?.takeIf { System.currentTimeMillis() - it.timestamp < rowTtlMs }?.let {
            return newHomePageResponse(
                listOf(HomePageList(request.name, it.items, isHorizontalImages = !vertical)),
                it.hasNext,
            )
        }
        val url = buildString {
            append("$apiBase/posts?per_page=12&page=$page&$listFields")
            append("&orderby=date&order=desc")
            if (catId > 0) append("&categories=$catId")
        }
        val (posts, hasNext) = fetchPosts(url)
        val items = postsToCards(posts, vertical)
        rowCache[key] = CachedRow(System.currentTimeMillis(), items, hasNext)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = !vertical)),
            hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$apiBase/posts?search=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&per_page=20&$listFields&orderby=relevance"
        val (posts, _) = fetchPosts(url)
        return postsToCards(posts)
    }

    private suspend fun fetchPost(id: Int, fields: String = postFields): WpPost? {
        if (id <= 0) return null
        return runCatching {
            parseJson<WpPost>(app.get("$apiBase/posts/$id?$fields").text).takeIf { it.id > 0 }
        }.getOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = runCatching { parseJson<LinkData>(url).id }.getOrNull() ?: 0
        fetchEpisode(id)?.let { return loadFromEpisode(url, id, it) }
        // Fallback for when the theme route is unavailable.
        return loadFromPost(url, id)
    }

    /** Title, plot, runtime, poster and servers in a single request. */
    private suspend fun loadFromEpisode(url: String, id: Int, episode: FspEpisode): LoadResponse {
        val seriesName = episode.series?.name?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
        val title = episodeTitle(episode) ?: seriesName ?: "Episode"
        val vertical = episode.series?.id?.let { it in verticalCategories } == true
        val recommendations = episode.series?.id?.takeIf { it > 0 }?.let { catId ->
            runCatching {
                val (related, _) = fetchPosts(
                    "$apiBase/posts?categories=$catId&exclude=$id" +
                        "&per_page=12&$listFields&orderby=date&order=desc"
                )
                postsToCards(related, vertical)
            }.getOrNull()
        }.orEmpty()
        val year = episode.year?.trim()?.toIntOrNull()
        val tags = buildList {
            seriesName?.let(::add)
            seasonLabel(episode.season, seriesName)
                ?.takeIf { it != episode.year?.trim() }
                ?.let(::add)
            episode.quality?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            formatApiDate(episode.date)?.let(::add)
        }.distinct().takeIf { it.isNotEmpty() }
        val poster = episode.thumbLg?.takeIf { it.startsWith("http") }
            ?: episode.thumb?.takeIf { it.startsWith("http") }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plotText(episode.desc)
            this.tags = tags
            this.year = year
            this.duration = runtimeMinutes(episode.runtime)
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun loadFromPost(url: String, id: Int): LoadResponse {
        val post = fetchPost(id) ?: throw IllegalStateException("Post not found")
        val title = post.title?.rendered?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: "Episode"
        val frag = post.content?.rendered?.let { Jsoup.parseBodyFragment(it) }
        val plot = plotText(frag?.selectFirst(".fsp-desc")?.text())
        // LoadResponse.duration is in minutes.
        val duration = frag?.select(".fsp-tag, .fsp-item-value")
            ?.firstNotNullOfOrNull { runtimeMinutes(it.text()) }
        val year = yearRegex.find(post.date.orEmpty())?.value?.toIntOrNull()
            ?: yearRegex.find(title)?.value?.toIntOrNull()
        // Poster, categories, and recommendations are independent — fetch them
        // concurrently instead of sequentially (server takes seconds per call).
        // Portrait art: detail posters and same-category recommendations stay vertical.
        val vertical = post.categories?.any { it in verticalCategories } == true
        val (poster, names, recommendations) = coroutineScope {
            val posterDeferred = async {
                if (post.featuredMedia > 0) fetchPosters(listOf(post.featuredMedia), vertical)[post.featuredMedia]
                else null
            }
            val namesDeferred = async { getCategoryNames() }
            val recsDeferred = async {
                post.categories?.firstOrNull()?.let { catId ->
                    runCatching {
                        val (related, _) = fetchPosts(
                            "$apiBase/posts?categories=$catId&exclude=${post.id}" +
                                "&per_page=12&$listFields&orderby=date&order=desc"
                        )
                        postsToCards(related, vertical)
                    }.getOrNull()
                }.orEmpty()
            }
            Triple(posterDeferred.await(), namesDeferred.await(), recsDeferred.await())
        }
        val tags = buildList {
            post.categories?.mapNotNullTo(this) { names[it] }
            post.date?.let(::formatDate)?.let(::add)
        }.distinct().takeIf { it.isNotEmpty() }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = runCatching { parseJson<LinkData>(data).id }.getOrNull() ?: 0
        fetchEpisode(id)?.let { episode ->
            val embeds = episode.servers.orEmpty().mapNotNull { server ->
                normalizeEmbed(server.embed)?.takeIf { !isJunkServer(it) }
            }.distinct()
            if (embeds.isNotEmpty()) {
                val referer = normalizeEmbed(episode.url) ?: mainUrl
                var found = false
                for (embed in embeds) {
                    runCatching {
                        loadExtractor(embed, referer, subtitleCallback) { found = true; callback(it) }
                    }
                }
                return found
            }
        }
        // Fallback for when the theme route is unavailable.
        val post = fetchPost(id, "_fields=id,link,content") ?: return false
        val html = post.content?.rendered?.takeIf { it.isNotBlank() } ?: return false
        val doc = Jsoup.parseBodyFragment(html)
        val embeds = linkedSetOf<String>()
        doc.select("[data-fsp-embed]").forEach { el ->
            normalizeEmbed(el.attr("data-fsp-embed"))?.let(embeds::add)
        }
        doc.select("iframe[src^=http]").forEach { el ->
            normalizeEmbed(el.attr("src"))?.let(embeds::add)
        }
        if (embeds.isEmpty()) return false
        val referer = post.link?.takeIf { it.startsWith("http") } ?: mainUrl
        var found = false
        for (embed in embeds) {
            runCatching {
                loadExtractor(embed, referer, subtitleCallback) { found = true; callback(it) }
            }
        }
        return found
    }
}
