package com.streamly

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

data class Provider(
    val id: String,
    val name: String,
    val category: String,
    val invoke: suspend (
        res: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) -> Boolean,
)

object ProvidersList {
    const val CATEGORY_VIDEO = "Sources"
    const val CATEGORY_SUBTITLES = "Subtitles"
    val categoryOrder = listOf(CATEGORY_VIDEO, CATEGORY_SUBTITLES)

    val providers: List<Provider> by lazy {
        listOf(
            Provider("topcinema", "TopCinema", CATEGORY_VIDEO) { res, sub, cb ->
                invokeTopCinema(res, sub, cb)
            },
            Provider("egydead", "EgyDead", CATEGORY_VIDEO) { res, sub, cb ->
                invokeEgyDead(res, sub, cb)
            },
            Provider("akwam", "Akwam", CATEGORY_VIDEO) { res, sub, cb ->
                invokeAkwam(res, sub, cb)
            },
            // EasyPlex app API (TMDB-keyed search, exact tmdb_id match).
            Provider("egywatch", "EgyWatch", CATEGORY_VIDEO) { res, sub, cb ->
                invokeEgyWatch(res, sub, cb)
            },
            // English embed fallback, fast on new releases (StreamPlay pattern).
            Provider("2embed", "2Embed", CATEGORY_VIDEO) { res, sub, cb ->
                invoke2Embed(res, sub, cb)
            },
            // Subtitle-only sources (StreamPlay pattern): Wyzie needs a user
            // key, SubtitleAPI is keyless. Both key off the IMDb id.
            Provider("wyziesubs", "WyzieSubs", CATEGORY_SUBTITLES) { res, sub, _ ->
                invokeWyzieSubs(res.imdbId, res.season, res.episode, sub)
            },
            Provider("subtitleapi", "SubtitleAPI", CATEGORY_SUBTITLES) { res, sub, _ ->
                invokeSubtitleApi(res.imdbId, res.season, res.episode, sub)
            },
        )
    }
}
