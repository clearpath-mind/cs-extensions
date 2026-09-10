package com.streamly

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class Data(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("type") val type: String? = null,
)

data class LinkData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
) {
    val isMovie get() = type == "movie"
}

data class Media(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

data class Results(
    @JsonProperty("results") val results: List<Media>? = null,
)

data class Genres(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class ExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null,
)

data class Videos(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class VideoResults(
    @JsonProperty("results") val results: List<Videos> = emptyList(),
)

data class Cast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class Credits(
    @JsonProperty("cast") val cast: List<Cast> = emptyList(),
)

data class Seasons(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
)

data class ReleaseDateEntry(
    @JsonProperty("certification") val certification: String? = null,
    @JsonProperty("iso_3166_1") val country: String? = null,
)

data class ReleaseDatesResult(
    @JsonProperty("iso_3166_1") val country: String? = null,
    @JsonProperty("release_dates") val entries: List<ReleaseDateEntry>? = null,
)

data class ReleaseDates(
    @JsonProperty("results") val results: List<ReleaseDatesResult>? = null,
)

data class ContentRatingEntry(
    @JsonProperty("iso_3166_1") val country: String? = null,
    @JsonProperty("rating") val rating: String? = null,
)

data class ContentRatings(
    @JsonProperty("results") val results: List<ContentRatingEntry>? = null,
)

data class MediaDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("original_language") val originalLanguage: String? = null,
    @JsonProperty("genres") val genres: List<Genres>? = null,
    @JsonProperty("seasons") val seasons: List<Seasons>? = null,
    @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
    @JsonProperty("videos") val videos: VideoResults? = null,
    @JsonProperty("credits") val credits: Credits? = null,
    @JsonProperty("recommendations") val recommendations: Results? = null,
    @JsonProperty("release_dates") val releaseDates: ReleaseDates? = null,
    @JsonProperty("content_ratings") val contentRatings: ContentRatings? = null,
)

data class Episodes(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("runtime") val runTime: Int? = null,
)

data class MediaDetailEpisodes(
    @JsonProperty("episodes") val episodes: List<Episodes> = emptyList(),
)

data class LogoEntry(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
)

data class LogoImages(
    @JsonProperty("logos") val logos: List<LogoEntry>? = null,
)

open class Streamly : MainAPI() {
    override var name = "Streamly"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private var appContext: Context? = null
    private var sharedPref: SharedPreferences? = null

    /** Called by the plugin entry point — MainAPI has no context hook here. */
    fun init(context: Context) {
        appContext = context.applicationContext
        sharedPref = runCatching {
            context.getSharedPreferences("streamly_prefs", Context.MODE_PRIVATE)
        }.getOrNull()
        StreamlyCache.loadProviderStats(sharedPref)
    }

    companion object {
        private const val TAG = "Streamly"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val apiKey = BuildConfig.TMDB_API
        // Display language for TMDB metadata (titles, plots, episode names)
        private const val LANG = "ar"
        // English is required for link resolution: TopCinema slugs are Latin-script
        private const val RESOLVER_LANG = "en-US"

        private fun getImageUrl(link: String?): String? {
            if (link == null) return null
            return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
        }

        private fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        private fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        /**
         * Picks a title logo from TMDB so the result page can render it in
         * place of the text title. Preference: Arabic raster logo → any
         * Arabic logo → highest-voted raster → highest-voted SVG.
         */
        suspend fun fetchLogoUrl(tmdbId: Int?, isMovie: Boolean): String? {
            if (tmdbId == null) return null
            val kind = if (isMovie) "movie" else "tv"
            val images = runCatching {
                app.get("$TMDB_API/$kind/$tmdbId/images?api_key=$apiKey", timeout = 8000)
                    .parsedSafe<LogoImages>()
            }.getOrNull() ?: return null

            val logos = images.logos.orEmpty()
                .filter { !it.filePath.isNullOrBlank() && it.filePath != "null" }
            if (logos.isEmpty()) return null

            fun isSvg(e: LogoEntry) = e.filePath!!.endsWith(".svg", ignoreCase = true)
            fun urlOf(e: LogoEntry) = "https://image.tmdb.org/t/p/w500${e.filePath}"
            fun voted(e: LogoEntry) = (e.voteAverage ?: 0.0) > 0.0 && (e.voteCount ?: 0) > 0
            val byVote = compareBy<LogoEntry>({ it.voteAverage ?: 0.0 }, { it.voteCount ?: 0 })

            logos.firstOrNull { it.lang == LANG && !isSvg(it) }?.let { return urlOf(it) }
            logos.firstOrNull { it.lang == LANG }?.let { return urlOf(it) }
            logos.filter { voted(it) && !isSvg(it) }.maxWithOrNull(byVote)?.let { return urlOf(it) }
            logos.filter { voted(it) }.maxWithOrNull(byVote)?.let { return urlOf(it) }
            return null
        }
    }

    // Needs to be a MainAPI member: newMovieSearchResponse is a MainAPI extension
    private fun Media.toSearchResponse(typeFallback: String? = null): SearchResponse? {
        val type = when (mediaType ?: typeFallback) {
            "movie" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> return null
        }
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: originalName ?: return null,
            Data(id = id, type = mediaType ?: typeFallback).toJson(),
            type,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score = Score.from10(voteAverage)
        }
    }

    override val mainPage = mainPageOf(
        "/trending/all/day?api_key=$apiKey&region=US" to "Trending",
        "/trending/movie/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular Movies",
        "/trending/tv/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular TV Shows",
        "/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en" to "Airing Today TV Shows",
        "/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "/discover/tv?api_key=$apiKey&with_networks=4330" to "Paramount+",
        "/discover/tv?api_key=$apiKey&with_networks=3353" to "Peacock",
        "/movie/top_rated?api_key=$apiKey&region=US" to "Top Rated Movies",
        "/tv/top_rated?api_key=$apiKey&region=US" to "Top Rated TV Shows",
        "/discover/tv?api_key=$apiKey&with_genres=99" to "Documentary",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        StreamlyRuntime.context = CommonActivity.activity
        // Non-trending TMDB endpoints (/movie/*, /discover/movie) omit media_type in results
        val fallbackType = if (request.data.contains("/movie")) "movie" else "tv"

        val home = app.get(
            url = "$TMDB_API${request.data}&language=$LANG&page=$page",
            timeout = 10000,
        ).parsedSafe<Results>()?.results?.mapNotNull { it.toSearchResponse(fallbackType) } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        StreamlyRuntime.context = CommonActivity.activity
        return app.get("$TMDB_API/search/multi?api_key=$apiKey&language=$LANG&query=$query&page=$page&include_adult=false")
            .parsedSafe<Results>()?.results
            ?.mapNotNull { it.toSearchResponse() }
            ?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        StreamlyRuntime.context = CommonActivity.activity
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val append = if (type == TvType.Movie) {
            "external_ids,videos,credits,recommendations,release_dates"
        } else {
            "external_ids,videos,credits,recommendations,content_ratings"
        }

        val resUrl = if (type == TvType.Movie) {
            "$TMDB_API/movie/${data.id}?api_key=$apiKey&language=$LANG&append_to_response=$append"
        } else {
            "$TMDB_API/tv/${data.id}?api_key=$apiKey&language=$LANG&append_to_response=$append"
        }

        val res = app.get(resUrl, timeout = 10000).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        // English detail for the link resolver (TopCinema slugs are Latin-script)
        val enResUrl = if (LANG != RESOLVER_LANG) {
            if (type == TvType.Movie) {
                "$TMDB_API/movie/${data.id}?api_key=$apiKey&language=$RESOLVER_LANG"
            } else {
                "$TMDB_API/tv/${data.id}?api_key=$apiKey&language=$RESOLVER_LANG"
            }
        } else null

        val enRes = enResUrl?.let {
            runCatching { app.get(it, timeout = 8000).parsedSafe<MediaDetail>() }.getOrNull()
        }

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath)
        val bgPoster = getImageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val actors = res.credits?.cast?.mapNotNull { cast ->
            val actorName = cast.name ?: return@mapNotNull null
            ActorData(Actor(actorName, getImageUrl(cast.profilePath)), roleString = cast.character)
        } ?: emptyList()
        val recommendations = res.recommendations?.results
            ?.mapNotNull { it.toSearchResponse(if (type == TvType.Movie) "movie" else "tv") }
        val trailer = res.videos?.results.orEmpty()
            .filter { it.type == "Trailer" }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        // English title is what TopCinema slugs use for matching
        val searchTitle = enRes?.title ?: enRes?.name ?: title
        // Raw TMDB certification (PG-13, R, TV-MA…): US first, else first rated entry.
        val contentRating = res.releaseDates?.results
            ?.flatMap { r -> (r.entries.orEmpty()).map { (r.country ?: "") to (it.certification.orEmpty()) } }
            .orEmpty()
            .firstOrNull { it.first == "US" && it.second.isNotBlank() }?.second
            ?: res.releaseDates?.results
                ?.flatMap { it.entries.orEmpty() }
                ?.mapNotNull { it.certification?.takeIf { c -> c.isNotBlank() } }
                ?.firstOrNull()
            ?: res.contentRatings?.results
                ?.firstOrNull { it.country == "US" && !it.rating.isNullOrBlank() }?.rating
            ?: res.contentRatings?.results
                ?.mapNotNull { it.rating?.takeIf { r -> r.isNotBlank() } }
                ?.firstOrNull()

        if (type == TvType.TvSeries) {
            var logoUrl: String? = null
            val episodes: List<Episode> = coroutineScope {
                val logoDeferred = async {
                    withTimeoutOrNull(3000L) { fetchLogoUrl(data.id, isMovie = false) }
                }
                val eps = res.seasons?.amap { season ->
                    async {
                        try {
                            app.get(
                                "$TMDB_API/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=$LANG",
                                timeout = 10000
                            ).parsedSafe<MediaDetailEpisodes>()
                                ?.episodes
                                ?.map { eps ->
                                    newEpisode(
                                        LinkData(
                                            id = data.id,
                                            type = data.type,
                                            title = searchTitle,
                                            year = year,
                                            season = eps.seasonNumber,
                                            episode = eps.episodeNumber,
                                        ).toJson()
                                    ) {
                                        this.name = eps.name
                                        this.season = eps.seasonNumber
                                        this.episode = eps.episodeNumber
                                        this.posterUrl = getImageUrl(eps.stillPath)
                                        val showMeta = sharedPref?.getBoolean("show_episode_meta", false) ?: false
                                        if (showMeta) {
                                            this.score = Score.from10(eps.voteAverage)
                                            this.description = eps.overview
                                        }
                                        this.runTime = eps.runTime
                                    }.apply {
                                        addDate(eps.airDate)
                                    }
                                } ?: emptyList()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load season ${season.seasonNumber}: ${e.message}")
                            emptyList()
                        }
                    }
                }?.awaitAll()?.flatten() ?: emptyList()
                logoUrl = logoDeferred.await()
                eps
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = genres
                this.score = Score.from10(res.voteAverage)
                this.showStatus = getStatus(res.status)
                if (!contentRating.isNullOrBlank()) this.contentRating = contentRating
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addImdbId(res.externalIds?.imdbId)
            }
        } else {
            val logoUrl = withTimeoutOrNull(3000L) { fetchLogoUrl(data.id, isMovie = true) }
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    id = data.id,
                    type = data.type,
                    title = searchTitle,
                    year = year,
                ).toJson(),
            ) {
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = genres
                this.score = Score.from10(res.voteAverage)
                this.contentRating = contentRating
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addImdbId(res.externalIds?.imdbId)
            }
        }
    }

    private fun normalizeUrl(url: String): String {
        var u = url.trim()
        // Lower-case host for stable dedup; keep path/query case-sensitive.
        runCatching {
            val uri = java.net.URI(u)
            val host = uri.host?.lowercase()
            if (host != null) {
                val scheme = uri.scheme?.lowercase() ?: "https"
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val path = uri.rawPath ?: ""
                val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                val frag = if (uri.fragment != null) "#${uri.fragment}" else ""
                u = "$scheme://$host$port$path$query$frag"
            }
        }
        if (u.endsWith("/")) u = u.trimEnd('/')
        return u
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        StreamlyRuntime.context = CommonActivity.activity
        val res = parseJson<LinkData>(data)

        // Prioritize providers by past success rate; circuit-broken ones sink.
        val disabled = sharedPref?.getStringSet("disabled_providers", emptySet()) ?: emptySet()
        val prioritized = ProvidersList.providers
            .filter { !disabled.contains(it.id) }
            .sortedByDescending {
                StreamlyCache.getProviderPriorityScore(it.id)
            }
        val broken = prioritized.count { StreamlyCache.getProviderStats(it.id).isCircuitBroken }
        if (broken > 0) {
            Log.d(TAG, "$broken slow/failing link sources moved to end of queue")
        }

        // Global dedup across all providers: same URL from
        // multiple sites (e.g. vidtube on TopCinema + Wecima) must not duplicate.
        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubs = ConcurrentHashMap.newKeySet<String>()
        // Dedup by normalized final URL only (allSeenLinks style).
        // Same host different quality with distinct variant URLs stays distinct (quality-specific),
        // same URL appearing via multiple providers (WeCima cinemm x2) collapses.
        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            val key = normalizeUrl(link.url)
            if (seenLinks.add(key)) callback(link)
            else Log.d(TAG, "[dedup ] skip duplicate ${link.name}: ${link.url}")
        }
        val dedupSub: (SubtitleFile) -> Unit = { sub ->
            val key = sub.url.trim()
            if (key.isBlank() || seenSubs.add(key)) subtitleCallback(sub)
        }

        val tasks: List<suspend () -> Unit> = prioritized.map { provider ->
            suspend {
                val startTime = System.currentTimeMillis()
                var success = false
                runCatching {
                    success = provider.invoke(res, dedupSub, dedupCallback)
                }.onFailure { e ->
                    Log.w(TAG, "${provider.name} failed, retrying: ${e.message}")
                    delay(1500)
                    runCatching {
                        success = provider.invoke(res, dedupSub, dedupCallback)
                        Log.d(TAG, "Retry succeeded: ${provider.name}")
                    }.onFailure { retryError ->
                        Log.e(TAG, "${provider.name} failed after retry: ${retryError.message}")
                    }
                }
                StreamlyCache.recordProviderExecution(
                    provider.id,
                    success,
                    System.currentTimeMillis() - startTime,
                )
            }
        }

        StreamlyConcurrency.runLimitedAsync(appContext, *tasks.toTypedArray())
        sharedPref?.let { StreamlyCache.saveProviderStats(it) }
        if (seenLinks.isNotEmpty()) Log.d(TAG, "[done  ] distinct links=${seenLinks.size}")
        true
    }
}

