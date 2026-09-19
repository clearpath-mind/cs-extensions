package com.bein

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BeINSportsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BeINSportsProvider())
    }
}
