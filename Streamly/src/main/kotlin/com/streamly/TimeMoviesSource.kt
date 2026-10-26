package com.streamly

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.streamly.timemovies.TimeMoviesApi
import com.streamly.timemovies.TimeMoviesExtractors
import com.streamly.timemovies.TimeMoviesModels
import com.streamly.timemovies.TimeMoviesPrefs
import com.streamly.timemovies.TimeMoviesUnlimitedEngine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

suspend fun invokeTimeMovies(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    StreamlyDiag.lastStage = "TimeMovies: start"
    if (title.isEmpty()) return false

    // Ensure prefs/auth initialized
    StreamlyRuntime.context?.let { TimeMoviesPrefs.init(it) }

    if (!TimeMoviesApi.hasUsableAuth() && !TimeMoviesUnlimitedEngine.isRuntimeActive()) {
        StreamlyDiag.lastStage = "TimeMovies: no auth"
        return false
    }

    var emitted = 0
    val counting: (ExtractorLink) -> Unit = { link -> emitted++; callback(relabelLink(link, "TimeMovies")) }

    return try {
        val page = TimeMoviesApi.searchTriple(title, page0Based = 0) ?: run {
            StreamlyDiag.lastStage = "TimeMovies: search failed"
            return false
        }
        val preferAr = TimeMoviesPrefs.preferArabic
        val candidates = (page.movies + page.series)
        if (candidates.isEmpty()) {
            StreamlyDiag.lastStage = "TimeMovies: no results"
            return false
        }
        val wantSeries = !res.isMovie
        var best = candidates.firstOrNull { (it.isMovie != true) == wantSeries }
            ?: candidates.firstOrNull()
        // fuzzy title match
        var bestScore = 0
        for (c in candidates) {
            val isSeries = (c.isMovie != true)
            if (isSeries != wantSeries) continue
            val name = if (preferAr) (c.titleAr ?: c.titleEn) else (c.titleEn.ifBlank { c.titleAr.orEmpty() })
            val score = me.xdrop.fuzzywuzzy.FuzzySearch.ratio(title.lowercase(), name.lowercase())
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (best == null || bestScore < 50) {
            StreamlyDiag.lastStage = "TimeMovies: no match"
            return false
        }
        val details = TimeMoviesApi.getWorkDetails(best.id) ?: run {
            StreamlyDiag.lastStage = "TimeMovies: no details"
            return false
        }
        val isMovie = details.isMovie
        val isAnimeWork = classifyAnime(details)
        val fileCode = details.fileCode.orEmpty()
        if (fileCode.isBlank() && !TimeMoviesUnlimitedEngine.isRuntimeActive()) {
            StreamlyDiag.lastStage = "TimeMovies: no file code"
            return false
        }
        val linkData = TimeMoviesModels.LinkPayload(
            workId = details.id,
            fileCode = fileCode,
            isMovie = isMovie,
            title = details.titleEn.ifBlank { title },
            titleAr = details.titleAr?.takeIf { it.isNotBlank() },
            year = details.releaseYear,
            isAnime = isAnimeWork,
            originalTitle = details.originalTitle?.takeIf { it.isNotBlank() },
        )
        // For series, find episode
        val payload = if (isMovie) {
            linkData
        } else {
            val allEps = details.seasons.flatMap { s -> s.episodes.map { s to it } }
            val matched = allEps.firstOrNull { (_, ep) -> ep.seasonNum == (res.season ?: 1) && ep.episodeNum == (res.episode ?: 1) }
                ?: allEps.firstOrNull()
            if (matched == null) {
                StreamlyDiag.lastStage = "TimeMovies: no episode"
                return false
            }
            val (season, ep) = matched
            linkData.copy(
                episodeId = ep.id,
                seasonId = season.id,
                seasonNum = ep.seasonNum,
                episodeNum = ep.episodeNum,
                fileCode = ep.fileCode ?: fileCode,
            )
        }
        val ok = withContext(Dispatchers.IO) {
            TimeMoviesExtractors.extractAllLinks(
                payload = payload,
                subtitleCallback = subtitleCallback,
                callback = counting,
            )
        }
        StreamlyDiag.lastStage = if (emitted > 0) "TimeMovies: ok" else "TimeMovies: no links"
        ok || emitted > 0
    } catch (e: Exception) {
        StreamlyDiag.lastStage = "TimeMovies: ${e.message}"
        emitted > 0
    }
}

private fun classifyAnime(details: com.streamly.timemovies.TimeMoviesModels.WorkDetails): Boolean {
    val joined = (details.genresEn + details.genresAr).joinToString(" ").lowercase()
    return joined.contains("animation") || joined.contains("anime") ||
        joined.contains("انيميشن") || joined.contains("أنمي") || joined.contains("انمي") || joined.contains("كرتون")
}
