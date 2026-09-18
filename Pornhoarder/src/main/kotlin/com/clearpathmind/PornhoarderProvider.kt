package com.clearpathmind

import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Wishonly
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornhoarderProvider : Plugin() {
    override fun load(context: Context) {
        CloudflareSolver.init(context)
        registerMainAPI(Pornhoarder())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Wishonly())
    }
}
