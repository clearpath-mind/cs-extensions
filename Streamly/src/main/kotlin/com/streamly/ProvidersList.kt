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
            // EasyPlex app API (TMDB-keyed search, exact tmdb_id match).
            Provider("egywatch", "EgyWatch") { res, sub, cb ->
                invokeEgyWatch(res, sub, cb)
            },
            Provider("cimacloud", "CimaCloud") { res, sub, cb ->
                invokeCimaCloud(res, sub, cb)
            },
            Provider("dramaworld", "DramaWorld") { res, sub, cb ->
                invokeDramaWorld(res, sub, cb)
            },
            Provider("timemovies", "TimeMovies") { res, sub, cb ->
                invokeTimeMovies(res, sub, cb)
            },
        )
    }
}
