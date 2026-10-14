package com.streamly

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

data class Provider(
    val id: String,
    val name: String,
    val invoke: suspend (
        res: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) -> Boolean,
)

object ProvidersList {
    val providers: List<Provider> by lazy {
        listOf(
            Provider("topcinema", "TopCinema") { res, sub, cb ->
                invokeTopCinema(res, sub, cb)
            },
            Provider("faselhd", "FaselHD") { res, sub, cb ->
                invokeFaselHd(res, sub, cb)
            },
            Provider("egydead", "EgyDead") { res, sub, cb ->
                invokeEgyDead(res, sub, cb)
            },
            Provider("akwam", "Akwam") { res, sub, cb ->
                invokeAkwam(res, sub, cb)
            },
            // EasyPlex app API (TMDB-keyed search, exact tmdb_id match).
            Provider("egywatch", "EgyWatch") { res, sub, cb ->
                invokeEgyWatch(res, sub, cb)
            },
            // English embed fallback, fast on new releases (StreamPlay pattern).
            Provider("2embed", "2Embed") { res, sub, cb ->
                invoke2Embed(res, sub, cb)
            },
            // VidLink: encrypted m3u8 player (port from StreamPlay).
            Provider("vidlink", "VidLink") { res, sub, cb ->
                invokeVidlink(res, sub, cb)
            },
            // MovieBox: multi-quality source with captions (port from StreamPlay).
            Provider("moviebox", "MovieBox") { res, sub, cb ->
                invokeMovieBox(res, sub, cb)
            },
            // Subtitle-only sources (StreamPlay pattern): Wyzie needs a user
            // key, SubtitleAPI is keyless. Both key off the IMDb id.
            Provider("wyziesubs", "WyzieSubs") { res, sub, _ ->
                invokeWyzieSubs(res.imdbId, res.season, res.episode, sub)
            },
            Provider("subtitleapi", "SubtitleAPI") { res, sub, _ ->
                invokeSubtitleApi(res.imdbId, res.season, res.episode, sub)
            },
        )
    }
}
