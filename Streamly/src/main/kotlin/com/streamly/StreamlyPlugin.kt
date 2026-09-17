package com.streamly

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.streamly.settings.StreamlyMainSettingsFragment

@CloudstreamPlugin
class StreamlyPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = Streamly()
        provider.init(context)
        registerMainAPI(provider)
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(UpDown())
        registerExtractorAPI(Dooood())
        registerExtractorAPI(MixDropPs())
        registerExtractorAPI(Filelion())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(Uqload())
        registerExtractorAPI(AnaFast())

        val sharedPref = context.getSharedPreferences("streamly_prefs", Context.MODE_PRIVATE)
        openSettings = { ctx ->
            val act = ctx as AppCompatActivity
            if (!act.isFinishing && !act.isDestroyed) {
                StreamlyMainSettingsFragment(this, sharedPref)
                    .show(act.supportFragmentManager, "streamly_settings")
            }
        }
    }
}
