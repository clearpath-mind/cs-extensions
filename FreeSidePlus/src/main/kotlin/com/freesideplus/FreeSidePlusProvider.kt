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
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

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

    // Top categories by post count (verified via /categories?orderby=count).
    override val mainPage = mainPageOf(
        "0" to "Latest Episodes",
        "36" to "BTS",
        "34" to "Sidecast",
        "44" to "Ask the Sidemen",
        "35" to "Sidemen Sunday",
        "57" to "Fine or Fucked",
        "31" to "Game Shows",
        "37" to "Side+ Saturdays",
    )

    private val postFields = "_fields=id,link,date,title,content,categories,featured_media"
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")

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

    private fun unescape(s: String): String = Parser.unescapeEntities(s, false).trim()

    /** Landscape thumbnail for horizontal cards (390x220 crop, else full). */
    private fun landscapeUrl(media: WpMedia): String? {
        val crop = media.mediaDetails?.sizes?.get("jannah-image-large")?.sourceUrl
            ?.takeIf { it.startsWith("http") }
        if (crop != null) return crop
        return media.sourceUrl?.takeIf { it.startsWith("http") }
    }

    private suspend fun fetchPosters(ids: List<Int>): Map<Int, String> {
        val distinct = ids.filter { it > 0 }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return runCatching {
            val url = "$apiBase/media?include=${distinct.joinToString(",")}" +
                "&per_page=100&_fields=id,source_url,media_details"
            parseJson<List<WpMedia>>(app.get(url).text).mapNotNull { media ->
                val thumb = landscapeUrl(media) ?: return@mapNotNull null
                media.id to thumb
            }.toMap()
        }.getOrNull().orEmpty()
    }

    private fun toSearchResponse(post: WpPost, posters: Map<Int, String>): SearchResponse? {
        if (post.id <= 0) return null
        val title = post.title?.rendered?.let(::unescape)?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, LinkData(post.id).toJson(), TvType.Movie) {
            this.posterUrl = posters[post.featuredMedia]
        }
    }

    private suspend fun fetchPosts(url: String): Pair<List<WpPost>, Boolean> {
        val res = app.get(url)
        val posts = runCatching { parseJson<List<WpPost>>(res.text) }.getOrNull().orEmpty()
        val totalPages = res.headers.entries
            .firstOrNull { it.key.equals("X-WP-TotalPages", ignoreCase = true) }
            ?.value?.toIntOrNull()
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
        val url = buildString {
            append("$apiBase/posts?per_page=12&page=$page&$postFields")
            append("&orderby=date&order=desc")
            if (catId > 0) append("&categories=$catId")
        }
        val (posts, hasNext) = fetchPosts(url)
        val items = postsToCards(posts)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$apiBase/posts?search=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&per_page=20&$postFields&orderby=relevance"
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
        val title = post.title?.rendered?.let(::unescape)?.takeIf { it.isNotBlank() } ?: "Episode"
        val frag = post.content?.rendered?.let { Jsoup.parseBodyFragment(it) }
        val plot = frag?.selectFirst(".fsp-desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val tags = frag?.select(".fsp-tag")?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }?.distinct()?.takeIf { it.isNotEmpty() }
        val names = getCategoryNames()
        val cats = post.categories?.mapNotNull { names[it] }.orEmpty()
        val year = tags?.firstNotNullOfOrNull { yearRegex.find(it)?.value?.toIntOrNull() }
            ?: yearRegex.find(title)?.value?.toIntOrNull()
            ?: yearRegex.find(post.date.orEmpty())?.value?.toIntOrNull()
        val poster = if (post.featuredMedia > 0) {
            fetchPosters(listOf(post.featuredMedia))[post.featuredMedia]
        } else null
        val pageUrl = post.link?.takeIf { it.startsWith("http") } ?: "$mainUrl/?p=${post.id}"
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = (tags.orEmpty() + cats).distinct().takeIf { it.isNotEmpty() }
            this.year = year
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
