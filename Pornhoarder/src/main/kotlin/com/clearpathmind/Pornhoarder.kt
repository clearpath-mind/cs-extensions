package com.clearpathmind

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element

class Pornhoarder : MainAPI() {
    override var mainUrl = "https://ww8.pornhoarder.org"
    override var name = "PornHoarder"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    /**
     * Mirror rotation: subdomains rotate (ww3 → ww8 …) and the bare domain is
     * Cloudflare edge-broken (Error 1034). Every request cycles the hosts.
     */
    private val mirrorHosts get() = MIRROR_HOSTS

    private fun withHost(url: String, host: String): String {
        mirrorHosts.forEach { h ->
            if (url.startsWith(h)) return host + url.removePrefix(h)
        }
        return if (url.startsWith("http")) url else host + url
    }

    /** GET with mirror fallback: primary → fallback on edge-dead pages. */
    private suspend fun getDoc(url: String) = fetchWithFallback(url, isPost = false, body = null)

    /** POST with mirror fallback. */
    private suspend fun postDoc(url: String, body: FormBody) =
        fetchWithFallback(url, isPost = true, body = body)

    private suspend fun fetchWithFallback(
        url: String,
        isPost: Boolean,
        body: FormBody?
    ): org.jsoup.nodes.Document {
        val attempts = mirrorHosts.map { withHost(url, it) }.distinct()
        var lastEdgeError: ErrorLoadingException? = null
        var lastBlocked: ErrorLoadingException? = null
        for (attempt in attempts) {
            // Auto-solve layer: challenge pages are cleared in a hidden
            // WebView and retried with the clearance cookies — no manual
            // settings dialog involved.
            val html = if (isPost && body != null) {
                phCfPostForm(attempt, body, referer = "$mainUrl/")
            } else {
                phCfGetText(attempt, referer = "$mainUrl/")
            }
            if (EDGE_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                lastEdgeError = ErrorLoadingException("Site unreachable (Cloudflare edge) — try again later")
                continue
            }
            if (isPhCfChallenge(html)) {
                lastBlocked = ErrorLoadingException("Cloudflare challenge — auto-solve failed, try again later")
                continue
            }
            return org.jsoup.Jsoup.parse(html)
        }
        throw lastBlocked ?: lastEdgeError
        ?: ErrorLoadingException("Site unreachable (Cloudflare edge) — try again later")
    }

    // Verified listing pattern (user-reported):
    // Latest  → /search/?search=&sort=0
    // Popular → /search/?search=&sort=2
    override val mainPage = mainPageOf(
        "$mainUrl/search/?search=&sort=0" to "Latest Videos",
        "$mainUrl/search/?search=&sort=2" to "Popular Videos",
        "$mainUrl/trending-videos/" to "Trending Videos",
        "$mainUrl/random-videos/" to "Random Videos"
    )

    /**
     * Forces orientation to Straight (value 0) once per session via the
     * site's own settings form (POST /settings/). Silent on failure —
     * never breaks listings. A challenge on the POST is auto-solved and
     * retried once. Response cookies are merged into the persisted store
     * (explicit Cookie headers bypass NiceHttp's jar).
     */
    private var orientationEnsured = false

    private suspend fun ensureStraightOrientation() {
        if (orientationEnsured) return
        orientationEnsured = true
        try {
            val body = FormBody.Builder()
                .add("sexual-orientation", "0")
                .add("theme", "1")
                .add("size", "0")
                .add("web_size", "1")
                .build()
            val firstTarget = phApplyHostOverride(withHost("$mainUrl/settings/", mainUrl))
            var res = app.post(
                firstTarget,
                requestBody = body,
                headers = phCfHeaders(firstTarget, "$mainUrl/", emptyMap()),
                timeout = 30000
            )
            // Explicit String? type: app response accessors carry a jspecify
            // @Nullable annotation that isn't on the compile classpath.
            val firstText: String? = res.text
            if (firstText != null && isPhCfChallenge(firstText)) {
                val solved = phCfSolve(firstTarget)
                val retryUrl = if (solved != null) phSolvedRetryUrl(solved, firstTarget) else firstTarget
                res = app.post(
                    retryUrl,
                    requestBody = body,
                    headers = phCfHeaders(retryUrl, "$mainUrl/", emptyMap()),
                    timeout = 30000
                )
            }
            // Collect Set-Cookie from every hop via the wrapped okhttp
            // response: the settings POST answers with a 302 redirect and
            // the preference cookie is set on the intermediate response.
            val allSetCookies = mutableListOf<String>()
            var prior = res.okhttpResponse.priorResponse
            while (prior != null) {
                allSetCookies.addAll(prior.headers.values("Set-Cookie"))
                prior = prior.priorResponse
            }
            allSetCookies.addAll(res.headers.values("Set-Cookie"))
            CloudflareSolver.mergeCookies(allSetCookies)
        } catch (_: Exception) {
        }
    }

    private var baseCookiesEnsured = false

    /** Merges the user-approved baked cookies (Straight config) into the
     * persisted store so every request carries them from the first call. */
    private fun ensureBaseCookies() {
        if (baseCookiesEnsured) return
        baseCookiesEnsured = true
        try {
            CloudflareSolver.mergeCookies(
                BAKED_COOKIES.map { (k, v) -> "$k=$v; path=/" }
            )
        } catch (_: Exception) {
        }
    }

    /** Tries several card selectors: listing templates differ per page type. */
    private val articleSelectors = listOf(
        ".video article",
        ".videos article",
        ".video-list article",
        ".video-item",
        ".thumb-block",
        ".video-block",
        "article"
    )

    private fun selectArticles(root: org.jsoup.nodes.Element): List<SearchResponse> {
        for (sel in articleSelectors) {
            val r = root.select(sel).mapNotNull { it.toSearchResult() }
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    /** "Similar Videos" section → recommendations; falls back to page cards. */
    private fun similarVideos(
        doc: org.jsoup.nodes.Document,
        currentUrl: String
    ): List<SearchResponse>? {
        // Pinned exact path first (verified page structure).
        var recs: List<SearchResponse> = doc
            .select(".video-detail-right .video-list .video article, .video-list .video article")
            .mapNotNull { it.toSearchResult() }
        if (recs.isEmpty()) {
            val heading = doc.select("h1, h2, h3, h4, .title, .heading, .page-header")
                .firstOrNull { it.text().contains("similar videos", ignoreCase = true) }
            if (heading != null) {
                var section: org.jsoup.nodes.Element? = heading.parent()
                var depth = 0
                while (section != null && depth < 4) {
                    recs = selectArticles(section)
                    if (recs.isNotEmpty()) break
                    section = section.parent()
                    depth++
                }
            }
        }
        if (recs.isEmpty()) recs = selectArticles(doc)
        return recs.filter { it.url != currentUrl }.take(24).ifEmpty { null }
    }

    /** Duration in minutes. Priority: exact info item
     * (.video-info .item[title*=duration]) → og:video:duration meta →
     * JSON-LD VideoObject "duration" → info-block time → page times.
     * Plain integers in meta/JSON-LD are seconds (e.g. 2036 → 33). */
    private fun extractDurationMinutes(doc: org.jsoup.nodes.Document): Int? {
        doc.selectFirst(".video-info .item[title*=duration], .video-details .item[title*=duration]")
            ?.text()?.let { parseDurationMinutes(it)?.let { m -> return m } }
        doc.select("script[type=application/ld+json]").forEach { s ->
            Regex(""""duration"\s*:\s*"([^"]+)"""").findAll(s.data()).forEach { m ->
                val raw = m.groupValues[1]
                parseSecondsPlain(raw)?.let { v -> return v }
                parseDurationMinutes(raw)?.let { v -> return v }
            }
        }
        val scope = doc.selectFirst(".video-info, .video-details, .video-meta") ?: doc
        for (sel in listOf(".duration", ".video-duration", "[class*=duration]", ".video-time", ".time")) {
            scope.select(sel).forEach { el ->
                parseDurationMinutes(el.text())?.let { m -> return m }
            }
        }
        // Last resorts: any duration-like string in JSON-LD, then the first
        // timestamp in the main content area (nav excluded structurally).
        doc.select("script[type=application/ld+json]").forEach { s ->
            parseDurationMinutes(s.data())?.let { m -> return m }
        }
        val main = doc.selectFirst("main") ?: doc
        parseDurationMinutes(main.text())?.let { m -> return m }
        return null
    }

    /** Plain second counts (10s … 12h); only for meta/JSON-LD values. */
    private fun parseSecondsPlain(text: String): Int? {
        val secs = text.trim().toLongOrNull() ?: return null
        if (secs < 10 || secs > 43200) return null
        return (secs / 60).toInt().coerceAtLeast(1)
    }

    private fun parseDurationMinutes(text: String): Int? {
        // ISO8601 with optional P (site emits "T33M56S" without it).
        Regex("""\bP?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(text)?.let { m ->
            if (m.value != "PT" && m.value != "T") {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                val s = m.groupValues[3].toIntOrNull() ?: 0
                return (h * 60 + mi + s / 60).coerceAtLeast(1)
            }
        }
        Regex("""\b(\d{1,3}):(\d{2})(?::(\d{2}))?\b""").find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].toIntOrNull() ?: 0
            val c = m.groupValues[3].toIntOrNull()
            return if (c != null) (a * 60 + b).coerceAtLeast(1) else if (a == 0) 1 else a
        }
        return null
    }

    /** Exact live filter set (verified search-page source: 13 checked servers,
     * sort 0=Date/1=Relevance/2=Popularity, date 0=Any time, author 0=Any).
     * The site's native mechanism is POST /ajax_search.php with this body. */
    private val serverIds = listOf(
        "47", "21", "40", "45", "12", "35", "25", "41", "44", "42", "43", "48", "29"
    )

    private fun searchPostBody(query: String, sort: String, page: Int): FormBody {
        val b = FormBody.Builder()
            .add("search", query)
            .add("sort", sort)
            .add("date", "0")
            .add("author", "0")
            .add("page", page.toString())
        serverIds.forEach { b.add("servers[]", it) }
        return b.build()
    }

    /** Native POST search; never throws (empty on any failure). */
    private suspend fun postSearch(query: String, sort: String, page: Int): List<SearchResponse> {
        return try {
            selectArticles(
                postDoc(withHost("$mainUrl/ajax_search.php", mainUrl), searchPostBody(query, sort, page))
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pageUrl(base: String, page: Int): String {
        val b = withHost(base, mainUrl)
        val sep = if (b.contains("?")) "&" else "?"
        return if (page > 1) "$b${sep}page=$page" else b
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        PornhoarderRuntime.context = CommonActivity.activity
        ensureBaseCookies()
        ensureStraightOrientation()
        val home: List<SearchResponse> = when (request.name) {
            // POST-first: full filter set returns the complete feed.
            "Latest Videos" ->
                postSearch("", "0", page).ifEmpty { selectArticles(getDoc(pageUrl(request.data, page))) }
            "Popular Videos" ->
                postSearch("", "2", page).ifEmpty { selectArticles(getDoc(pageUrl(request.data, page))) }
            else -> selectArticles(getDoc(pageUrl(request.data, page)))
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".video-content h1").text()
            .replace("| PornHoarder.tv", "").trim()
            .ifBlank {
                this.selectFirst("a[title]")?.attr("title")?.trim().orEmpty()
            }
            .ifBlank {
                this.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            }
            .ifBlank { return null }
        val rawHref = (
            this.select(".video-link").attr("href").ifBlank { null }
                ?: this.select("a.video-link").attr("href").ifBlank { null }
                ?: this.select(".video-image a").attr("href").ifBlank { null }
                ?: this.select("a[href^=/video]").attr("href").ifBlank { null }
                ?: return null
            )
        val href = fixUrlNull(if (rawHref.startsWith("http")) rawHref else mainUrl + rawHref)
            ?: return null
        // Thumbnails live in data-src when lazy, or inline background-image
        // style once loaded — cover both.
        val styleUrl = this.select("[style*=url]").firstNotNullOfOrNull { el ->
            Regex("""url\(['"]?(.*?)['"]?\)""")
                .find(el.attr("style"))?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val posterUrl = fixUrlNull(
            this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst(".video-image img")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("src")?.ifBlank { null }
                ?: styleUrl
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        PornhoarderRuntime.context = CommonActivity.activity
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        ensureBaseCookies()
        ensureStraightOrientation()
        // Reference feed: reject result sets identical to unfiltered Latest.
        val latestUrls = try {
            postSearch("", "0", 1).map { it.url }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        // Primary: native POST with the full filter set.
        val posted = postSearch(q, "0", page)
        if (posted.isNotEmpty() &&
            (latestUrls.isEmpty() || posted.map { it.url }.toSet() != latestUrls)
        ) {
            return newSearchResponseList(posted, true)
        }
        // Fallbacks: GET variants.
        val enc = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val pageSuffix = if (page > 1) "&page=$page" else ""
        val candidates = listOf(
            "$mainUrl/search/?search=$enc&sort=0$pageSuffix",
            "$mainUrl/search/?search=$enc$pageSuffix",
            "$mainUrl/search/?search=$enc&sort=2$pageSuffix",
            "$mainUrl/search/$enc/"
        )
        for (u in candidates) {
            try {
                val r = selectArticles(getDoc(u))
                if (r.isEmpty()) continue
                val urls = r.map { it.url }.toSet()
                if (latestUrls.isNotEmpty() && urls == latestUrls) continue
                return newSearchResponseList(r, true)
            } catch (_: Exception) {
            }
        }
        if (posted.isNotEmpty()) return newSearchResponseList(posted, true)
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun load(url: String): LoadResponse {
        PornhoarderRuntime.context = CommonActivity.activity
        val document = getDoc(url)
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")?.trim()?.replace("| PornHoarder.tv", "")
            ?.ifBlank { null } ?: "PornHoarder video"
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst(".video-detail-description p")?.text()?.trim()
                ?.ifBlank { null }
        val scope = document.selectFirst("main") ?: document
        val tags = (
            document.select(".video-tags a, .tags a, a[rel=tag]") +
                scope.select("a[href*=tag]")
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.contains(" ") }
            .distinct()
        // Pornstars list doubles as cast info on this template.
        val actors = document.select("ul.video-detail-list a.video-pornstar").mapNotNull { a ->
            val name = a.selectFirst("h4, .video-pornstar-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val image = fixUrlNull(
                a.selectFirst(".user-avatar-image")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            )
            Actor(name, image)
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags.ifEmpty { null }
            this.duration = extractDurationMinutes(document)
            this.recommendations = similarVideos(document, url)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        PornhoarderRuntime.context = CommonActivity.activity
        val doc = getDoc(data)
        val serverPages = mutableListOf(data)
        doc.select(".video-detail-servers li a").forEach { item ->
            fixUrlNull(mainUrl + item.attr("href"))?.let { serverPages.add(it) }
        }
        var found = false
        serverPages.distinct().forEach { pageUrl ->
            val pageDoc = if (pageUrl == data) doc else
                getDoc(pageUrl)
            val iframeSrc = fixUrlNull(pageDoc.selectFirst(".video-player iframe")?.attr("src"))
                ?: return@forEach
            val playBody = FormBody.Builder().addEncoded("play", "").build()
            val innerDoc = postDoc(iframeSrc, playBody)
            val hosterUrl = fixUrlNull(innerDoc.selectFirst("iframe")?.attr("src"))
                ?: return@forEach
            if (loadExtractor(hosterUrl, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    companion object {
        val MIRROR_HOSTS = listOf(
            "https://ww8.pornhoarder.org",
            "https://ww5.pornhoarder.org",
            "https://ww3.pornhoarder.org",
            "https://pornhoarder.tv"
        )
        /** User-approved Straight config baked into every session. */
        val BAKED_COOKIES = mapOf(
            "pornhoarder_settings" to "0---0---1---1",
            "pornhoarder_a" to "1"
        )
        private val EDGE_MARKERS = listOf(
            "edge ip restricted",
            "error 1034"
        )
    }
}
