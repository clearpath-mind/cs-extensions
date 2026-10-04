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

    // Full fields (with content HTML) — only for single-post load()/loadLinks().
    private val postFields = "_fields=id,link,date,title,content,categories,featured_media"
    // List fields (no content): the full post HTML is ~50x the payload
    // (122KB/12.8s vs 2.4KB/3.2s for 12 posts) and cards never use it.
    private val listFields = "_fields=id,link,date,title,categories,featured_media"
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val durationRegex = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")
    private val qualitySuffixRegex = Regex("""\s*\(?\d{3,4}p\)?\s*$""", RegexOption.IGNORE_CASE)

    private fun unescape(s: String): String = Parser.unescapeEntities(s, false).trim()

    // Media IDs repeat across Latest/category rows and paginations — cache hits
    // skip the /media round-trip entirely (server takes ~2-8s per call).
    private val posterCache = ConcurrentHashMap<Int, String>()

    private data class CachedRow(
        val timestamp: Long,
        val items: List<SearchResponse>,
        val hasNext: Boolean,
    )
    // Homepage has no app-level cache; a short TTL makes back-nav/initial
    // revisit instant without noticeably delaying new episodes.
    private val rowCache = ConcurrentHashMap<String, CachedRow>()
    private val rowTtlMs = 3 * 60 * 1000L

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

    /** Landscape thumbnail for horizontal cards (780x470 crop, else fallbacks). */
    private fun landscapeUrl(media: WpMedia): String? {
        val sizes = media.mediaDetails?.sizes
        val crop = sizes?.get("jannah-image-post")?.sourceUrl
            ?.takeIf { it.startsWith("http") }
            ?: sizes?.get("jannah-image-large")?.sourceUrl
                ?.takeIf { it.startsWith("http") }
        if (crop != null) return crop
        return media.sourceUrl?.takeIf { it.startsWith("http") }
    }

    private suspend fun fetchPosters(ids: List<Int>): Map<Int, String> {
        val wanted = ids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val cached = wanted.mapNotNull { id -> posterCache[id]?.let { id to it } }.toMap()
        val missing = wanted.filter { !posterCache.containsKey(it) }
        if (missing.isEmpty()) return cached
        val fetched = runCatching {
            val url = "$apiBase/media?include=${missing.joinToString(",")}" +
                "&per_page=100&_fields=id,source_url,media_details"
            parseJson<List<WpMedia>>(app.get(url).text).mapNotNull { media ->
                val thumb = landscapeUrl(media) ?: return@mapNotNull null
                media.id to thumb
            }.toMap()
        }.getOrNull().orEmpty()
        fetched.forEach { (id, url) -> posterCache[id] = url }
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

    private suspend fun postsToCards(posts: List<WpPost>): List<SearchResponse> {
        if (posts.isEmpty()) return emptyList()
        val posters = fetchPosters(posts.map { it.featuredMedia })
        return posts.mapNotNull { toSearchResponse(it, posters) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catId = request.data.toIntOrNull() ?: 0
        val key = "$catId:$page"
        rowCache[key]?.takeIf { System.currentTimeMillis() - it.timestamp < rowTtlMs }?.let {
            return newHomePageResponse(
                listOf(HomePageList(request.name, it.items, isHorizontalImages = true)),
                it.hasNext,
            )
        }
        val url = buildString {
            append("$apiBase/posts?per_page=12&page=$page&$listFields")
            append("&orderby=date&order=desc")
            if (catId > 0) append("&categories=$catId")
        }
        val (posts, hasNext) = fetchPosts(url)
        val items = postsToCards(posts)
        rowCache[key] = CachedRow(System.currentTimeMillis(), items, hasNext)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
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
        val post = fetchPost(id) ?: throw IllegalStateException("Post not found")
        val title = post.title?.rendered?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: "Episode"
        val frag = post.content?.rendered?.let { Jsoup.parseBodyFragment(it) }
        val plot = frag?.selectFirst(".fsp-desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
        // LoadResponse.duration is in minutes.
        val duration = frag?.select(".fsp-tag, .fsp-item-value")
            ?.firstNotNullOfOrNull { durationRegex.find(it.text()) }
            ?.let { match ->
                val parts = match.groupValues
                val totalSeconds = if (parts[3].isNotEmpty()) {
                    (parts[1].toIntOrNull() ?: 0) * 3600 +
                        (parts[2].toIntOrNull() ?: 0) * 60 +
                        (parts[3].toIntOrNull() ?: 0)
                } else {
                    (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
                }
                (totalSeconds + 30) / 60
            }
        val year = yearRegex.find(post.date.orEmpty())?.value?.toIntOrNull()
            ?: yearRegex.find(title)?.value?.toIntOrNull()
        // Poster, categories, and recommendations are independent — fetch them
        // concurrently instead of sequentially (server takes seconds per call).
        val (poster, names, recommendations) = coroutineScope {
            val posterDeferred = async {
                if (post.featuredMedia > 0) fetchPosters(listOf(post.featuredMedia))[post.featuredMedia]
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
                        postsToCards(related)
                    }.getOrNull()
                }.orEmpty()
            }
            Triple(posterDeferred.await(), namesDeferred.await(), recsDeferred.await())
        }
        val pageUrl = post.link?.takeIf { it.startsWith("http") } ?: "$mainUrl/?p=${post.id}"
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
        val post = fetchPost(id, "_fields=id,link,content") ?: return false
        val html = post.content?.rendered?.takeIf { it.isNotBlank() } ?: return false
        val doc = Jsoup.parseBodyFragment(html)
        val embeds = linkedSetOf<String>()
        doc.select("[data-fsp-embed]").forEach { el ->
            el.attr("data-fsp-embed").trim().takeIf { it.startsWith("http") }?.let(embeds::add)
        }
        doc.select("iframe[src^=http]").forEach { el ->
            el.attr("src").trim().takeIf { it.startsWith("http") }?.let(embeds::add)
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
