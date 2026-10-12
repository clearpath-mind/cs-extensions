package com.streamly

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lagradost.api.Log
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
        registerExtractorAPI(VidSpeed())
        registerExtractorAPI(CdnPlus())
        registerExtractorAPI(Mp4Plus())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(KwikExtractor())
        registerExtractorAPI(VidSrcExtractor())

        val sharedPref = context.getSharedPreferences("streamly_prefs", Context.MODE_PRIVATE)

        // Set recommended concurrency on first run (StreamPlay pattern):
        // device profile decides the default, the user can still change it.
        if (sharedPref.getInt("provider_concurrency", -1) == -1) {
            val recommended = StreamlyConcurrency.detectDeviceProfile(context).recommendedConcurrency
            sharedPref.edit {
                putInt("provider_concurrency", recommended)
            }
            Log.d("Streamly", "Set recommended concurrency: $recommended")
        }

        openSettings = { ctx ->
            val act = ctx as AppCompatActivity
            if (!act.isFinishing && !act.isDestroyed) {
                StreamlyMainSettingsFragment(this, sharedPref)
                    .show(act.supportFragmentManager, "streamly_settings")
            }
        }
    }
}
