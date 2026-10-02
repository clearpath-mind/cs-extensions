package com.freesideplus

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

class FreeSidePlusProvider : MainAPI() {
    override var mainUrl = "https://freesideplus.plus"
    override var name = "FreeSidePlus"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes",
        "$mainUrl/?cat=37" to "Side+ Saturdays",
        "$mainUrl/?cat=34" to "Sidecast",
        "$mainUrl/?cat=35" to "Sidemen Sunday",
        "$mainUrl/?cat=31" to "Game Shows",
        "$mainUrl/?cat=32" to "Debate Club",
        "$mainUrl/?cat=36" to "BTS",
        "$mainUrl/?cat=39" to "BTS 2026",
        "$mainUrl/?cat=38" to "Sideless Mondays",
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if ('?' in base) "$base&paged=$page" else "$base?paged=$page"
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        // Jannah theme cards first, generic post-link fallback second.
        val cards = doc.select(".post-item, article.post, .post-listing li, .mag-box li")
        for (el in cards) {
            val titleEl = el.selectFirst(".post-title a, .post-box-title a, h2 a, h3 a")
                ?: continue
            val href = titleEl.attr("href").takeIf { it.isNotBlank() }
                ?: el.selectFirst(".post-thumb a")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: continue
            val img = el.selectFirst(".post-thumb img, img")
            val poster = img?.let {
                it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
                    .ifBlank { it.attr("abs:data-lazy-src") }
            }?.takeIf { it.isNotBlank() && it.startsWith("http") }
            out.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        if (out.isNotEmpty()) return out
        // Fallback: any link to a post page (?p=ID) with an image nearby.
        for (a in doc.select("a[href*='?p=']")) {
            val href = a.attr("abs:href").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(href)) continue
            val title = a.attr("title").ifBlank { a.text() }.trim()
                .takeIf { it.isNotBlank() } ?: continue
            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
            val poster = img?.attr("abs:src")?.takeIf { it.startsWith("http") }
            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    private fun hasNextPage(doc: Document, page: Int, items: List<SearchResponse>): Boolean {
        if (doc.selectFirst("a.next, .pagination a.next, .pages-nav a.next, a[rel=next]") != null) {
            return true
        }
        val nextHref = doc.select("a[href*=paged]").map { it.attr("href") }
        if (nextHref.any { Regex("[?&]paged=${page + 1}\\b").containsMatchIn(it) }) return true
        // Homepage shows ~10 posts per page; assume more while pages are full.
        return items.size >= 10
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page)).document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(doc, page, items))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}").document
        return parseCards(doc)
    }

    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.post-title, h1.entry-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Episode"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst(".fsp-desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
        val tags = doc.select(".fsp-tag").map { it.text().trim() }.filter { it.isNotBlank() }
            .distinct().takeIf { it.isNotEmpty() }
        val year = tags?.firstNotNullOfOrNull { yearRegex.find(it)?.value?.toIntOrNull() }
            ?: yearRegex.find(title)?.value?.toIntOrNull()
            ?: yearRegex.find(doc.selectFirst(".post-meta, time")?.text().orEmpty())
                ?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data).document
        val embeds = linkedSetOf<String>()
        // Structured server buttons rendered by the theme.
        doc.select("[data-fsp-embed]").forEach { el ->
            el.attr("data-fsp-embed").trim().takeIf { it.startsWith("http") }?.let(embeds::add)
        }
        // Primary player + any fallback iframe.
        doc.select("iframe.fsp-player, .fsp-player-wrap iframe, iframe[src^=http]").forEach { el ->
            el.attr("src").trim().takeIf { it.startsWith("http") }?.let(embeds::add)
        }
        // Legacy stream anchors (skip /d/ download links).
        doc.select("a.fsp-source[href], a.fsp-btn[href]").forEach { el ->
            val href = el.attr("href").trim()
            if (href.startsWith("http") && "/d/" !in href) embeds.add(href)
        }
        var found = false
        for (embed in embeds) {
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { found = true; callback(it) }
            }
        }
        return found
    }
}
