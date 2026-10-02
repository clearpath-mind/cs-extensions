package com.freesideplus

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreeSidePlusPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FreeSidePlusProvider())
    }
}
