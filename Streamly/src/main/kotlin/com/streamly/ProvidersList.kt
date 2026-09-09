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
            Provider("wecima", "WeCima") { res, sub, cb ->
                invokeWecima(res, sub, cb)
            },
            Provider("egydead", "EgyDead") { res, sub, cb ->
                invokeEgydead(res, sub, cb)
            },
            Provider("faselhd", "FaselHD") { res, sub, cb ->
                invokeFaselHd(res, sub, cb)
            },
        )
    }
}
