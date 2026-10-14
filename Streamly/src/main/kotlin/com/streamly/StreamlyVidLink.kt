package com.streamly

import com.fasterxml.jackson.annotation.SerializedName
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URLDecoder

private const val VIDLINK_TAG = "VidLink"
private const val VIDLINK_API = "https://vidlink.pro"

// Vidlink
data class VidlinkResponse(
    @SerializedName("stream") val stream: VidlinkStream,
)

data class VidlinkStream(
    @SerializedName("playlist") val playlist: String,
)

/** VidLink provider ported from StreamPlay (v689). Uses an external enc-dec
 *  service to mint the encrypted id, then serves an m3u8 playlist. */
suspend fun invokeVidlink(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val tmdbId = res.id ?: return false
    var emitted = 0
    val counting: (ExtractorLink) -> Unit = { emitted++; callback(it) }

    return try {
        val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
        val encResponse = app.get(encUrl).text
        val encData = runCatching {
            org.json.JSONObject(encResponse).optString("result")
        }.getOrNull().takeIf { !it.isNullOrEmpty() } ?: return false

        val base = VIDLINK_API
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Connection" to "keep-alive",
            "Referer" to "$base/",
            "Origin" to base,
        )

        val apiUrl = if (res.isMovie) {
            "$base/api/b/movie/$encData"
        } else {
            val season = res.season ?: return false
            val episode = res.episode ?: return false
            "$base/api/b/tv/$encData/$season/$episode"
        }

        val epResponse = app.get(apiUrl, headers = headers).text
        val data = runCatching { Gson().fromJson(epResponse, VidlinkResponse::class.java) }.getOrNull()
            ?: return false
        val stream = data.stream
        val m3u8 = stream.playlist

        var referer = "$base/"
        var origin = base
        val headersJson = Regex("""[?&]headers=([^&]+)""")
            .find(m3u8)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
        if (!headersJson.isNullOrBlank()) {
            runCatching {
                val obj = Gson().fromJson(headersJson, JsonObject::class.java)
                obj["referer"]?.asString?.let { referer = it }
                obj["origin"]?.asString?.let { origin = it }
            }
        }
        val m3u8url = m3u8.substringBefore("?")
        generateM3u8(
            "VidLink",
            m3u8url,
            referer = referer,
            headers = mapOf(
                "Origin" to origin,
                "Referer" to referer,
            ),
        ).forEach(counting)

        Log.d(VIDLINK_TAG, "emitted=$emitted")
        emitted > 0
    } catch (e: Exception) {
        Log.e(VIDLINK_TAG, "failed: ${e.message}")
        emitted > 0
    }
}
