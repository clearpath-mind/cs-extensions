package com.streamly.settings

import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.streamly.StreamlyCache
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class StreamlyStremioAddon(
    val id: Long,
    val name: String,
    val url: String,
    val type: StreamlyStremioAddonType
)

enum class StreamlyStremioAddonType {
    SUBTITLE,
    TORRENT,
    HTTPS,
    DEBRID
}

object StreamlyStremioSettings {
    const val PREF_KEY_LINKS = "streamly_stremio_addon_saved_links"

    fun getStremioAddons(sharedPref: SharedPreferences?): List<StreamlyStremioAddon> {
        val json = sharedPref?.getString(PREF_KEY_LINKS, null) ?: return emptyList()
        val list = mutableListOf<StreamlyStremioAddon>()
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val link = obj.optString("link", "").trim()
                if (link.isEmpty()) continue
                list.add(
                    StreamlyStremioAddon(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", link).ifBlank { link },
                        url = link.fixStremioUrl().trimEnd('/'),
                        type = StreamlyStremioAddonType.values().firstOrNull {
                            it.name.equals(obj.optString("type", "HTTPS"), ignoreCase = true)
                        } ?: StreamlyStremioAddonType.HTTPS
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveStremioAddons(sharedPref: SharedPreferences?, addons: List<StreamlyStremioAddon>) {
        val arr = JSONArray()
        addons.forEach { addon ->
            arr.put(
                JSONObject()
                    .put("id", addon.id)
                    .put("name", addon.name)
                    .put("link", addon.url)
                    .put("type", addon.type.name)
            )
        }
        sharedPref?.edit()?.putString(PREF_KEY_LINKS, arr.toString())?.apply()
    }

    fun stremioAddonKey(name: String): String {
        val key = name
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "addon" }
        return "stremio_$key"
    }

    /** One race task per saved addon, keyed for stats like regular providers. */
    fun getDynamicStremioMap(
        sharedPref: SharedPreferences?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Map<String, suspend () -> Unit> {
        return getStremioAddons(sharedPref).associate { addon ->
            val key = stremioAddonKey(addon.name)
            key to suspend {
                val startTime = System.currentTimeMillis()
                var success = false
                val emitted = java.util.concurrent.atomic.AtomicInteger(0)
                val counting: (ExtractorLink) -> Unit = { emitted.incrementAndGet(); callback(it) }
                runCatching {
                    when (addon.type) {
                        StreamlyStremioAddonType.SUBTITLE -> {
                            invokeStremioSubtitles(addon.name, addon.url, imdbId, season, episode, subtitleCallback)
                            success = true
                        }
                        StreamlyStremioAddonType.TORRENT -> {
                            invokeStremioTorrents(addon.name, addon.url, imdbId, season, episode, counting)
                            success = emitted.get() > 0
                        }
                        StreamlyStremioAddonType.HTTPS, StreamlyStremioAddonType.DEBRID -> {
                            invokeStremioStreams(addon.name, addon.url, imdbId, season, episode, subtitleCallback, counting)
                            success = emitted.get() > 0
                        }
                    }
                }
                StreamlyCache.recordProviderExecution(key, success, System.currentTimeMillis() - startTime)
            }
        }
    }
}

private fun String.fixStremioUrl(): String {
    val trimmed = this.trim().trimEnd('/')
    if (trimmed.endsWith("/manifest.json", ignoreCase = true)) {
        return trimmed.dropLast("/manifest.json".length)
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("stremio://")) return "https://" + trimmed.removePrefix("stremio://")
    return "https://$trimmed"
}

private fun streamUrlFor(api: String, imdbId: String, season: Int?, episode: Int?): String {
    return if (season == null) "$api/stream/movie/$imdbId.json"
    else "$api/stream/series/$imdbId:$season:$episode.json"
}

private fun stremioTimeout(key: String): Long {
    return StreamlyCache.getAdaptiveTimeout(key, 15000L)
}

suspend fun invokeStremioTorrents(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    if (imdbId.isNullOrBlank()) return
    val url = streamUrlFor(api, imdbId, season, episode)
    val res = app.get(url, timeout = stremioTimeout("stremio_$sourceName"))
        .parsedSafe<StreamlyStremioResponse>() ?: return

    res.streams.forEach { stream ->
        val title = stream.description ?: stream.title ?: stream.name ?: ""
        val magnet = buildMagnetString(stream).takeIf { it.isNotBlank() } ?: return@forEach
        callback.invoke(
            newExtractorLink(
                "$sourceName Magnet",
                "[$sourceName] Magnet $title",
                magnet,
                ExtractorLinkType.MAGNET,
            ) {
                this.quality = getQualityFromName(title)
            }
        )
    }
}

suspend fun invokeStremioStreams(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (imdbId.isNullOrBlank()) return
    val url = streamUrlFor(api, imdbId, season, episode)
    val res = app.get(url, timeout = stremioTimeout("stremio_$sourceName"))
        .parsedSafe<StreamlyStremioResponse>() ?: return

    res.streams.forEach { s ->
        val title = s.description ?: s.title ?: s.name ?: ""
        val streamUrl = s.url

        if (!streamUrl.isNullOrBlank()) {
            val type = when {
                streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                streamUrl.endsWith(".torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                streamUrl.endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("hls", ignoreCase = true) -> ExtractorLinkType.M3U8
                else -> INFER_TYPE
            }
            val proxyReq = s.behaviorHints?.proxyHeaders?.request
            val stdHeaders = s.behaviorHints?.headers

            callback.invoke(
                newExtractorLink(
                    sourceName,
                    "[$sourceName] $title",
                    streamUrl,
                    type
                ) {
                    this.quality = getQualityFromName(title)
                    this.headers = mapOf(
                        "User-Agent" to (proxyReq.getHeader("User-Agent") ?: stdHeaders.getHeader("User-Agent") ?: USER_AGENT),
                        "Referer" to (proxyReq.getHeader("Referer") ?: stdHeaders.getHeader("Referer") ?: ""),
                        "Origin" to (proxyReq.getHeader("Origin") ?: stdHeaders.getHeader("Origin") ?: "")
                    ).filterValues { it.isNotBlank() }
                }
            )
        }

        s.externalUrl?.takeIf { it.isNotBlank() }?.let { loadExtractor(it, sourceName, subtitleCallback, callback) }
        s.ytId?.takeIf { it.isNotBlank() }?.let { loadExtractor("https://www.youtube.com/watch?v=$it", subtitleCallback, callback) }
        s.subtitles.forEach { emitStremioSubtitle(it, subtitleCallback) }
    }
}

suspend fun invokeStremioSubtitles(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    if (imdbId.isNullOrBlank()) return
    val url = if (season != null) "$api/subtitles/series/$imdbId:$season:$episode.json"
    else "$api/subtitles/movie/$imdbId.json"
    val subtitleResponse = app.get(url, timeout = stremioTimeout("stremio_sub_$sourceName"))
        .parsedSafe<StreamlyStremioSubtitleResponse>() ?: return
    subtitleResponse.subtitles.forEach { emitStremioSubtitle(it, subtitleCallback) }
}

private fun emitStremioSubtitle(subtitle: StreamlyStremioSubtitle, subtitleCallback: (SubtitleFile) -> Unit) {
    val lang = subtitle.lang ?: subtitle.langCode ?: return
    val fileUrl = subtitle.url ?: return
    subtitleCallback.invoke(newSubtitleFile(mapStremioLang(lang), fileUrl))
}

private fun mapStremioLang(code: String): String {
    return when (code.lowercase(Locale.ROOT).substringBefore("-").substringBefore("_")) {
        "ar" -> "Arabic"
        "en" -> "English"
        "fr" -> "French"
        "es" -> "Spanish"
        "de" -> "German"
        "hi" -> "Hindi"
        "tr" -> "Turkish"
        "ur" -> "Urdu"
        "fa" -> "Persian"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        else -> code
    }
}

private fun buildMagnetString(stream: StreamlyStremioStream): String {
    val url = stream.url.orEmpty()
    if (url.startsWith("magnet:", ignoreCase = true)) return url
    val infoHash = stream.infoHash?.takeIf { it.isNotBlank() } ?: return ""
    val title = stream.description ?: stream.title ?: stream.name ?: infoHash

    return buildString {
        append("magnet:?xt=urn:btih:").append(infoHash)
        append("&dn=").append(URLEncoder.encode(title, StandardCharsets.UTF_8.name()))
        stream.fileIdx?.let { append("&so=").append(it) }
        stream.sources.filter { it.startsWith("tracker:", ignoreCase = true) }
            .map { it.removePrefix("tracker:") }
            .filter { it.isNotBlank() }
            .forEach { append("&tr=").append(URLEncoder.encode(it, StandardCharsets.UTF_8.name())) }
    }
}

private fun Map<String, String>?.getHeader(name: String): String? {
    return this?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

data class StreamlyStremioManifest(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("version") val version: String? = null
)

data class StreamlyStremioResponse(@SerializedName("streams") val streams: List<StreamlyStremioStream> = emptyList())
data class StreamlyStremioBehaviorHints(@SerializedName("proxyHeaders") val proxyHeaders: StreamlyStremioProxyHeaders? = null, @SerializedName("headers") val headers: Map<String, String>? = null)
data class StreamlyStremioProxyHeaders(@SerializedName("request") val request: Map<String, String>? = null)
data class StreamlyStremioSubtitleResponse(@SerializedName("subtitles") val subtitles: List<StreamlyStremioSubtitle> = emptyList())
data class StreamlyStremioSubtitle(@SerializedName("url") val url: String? = null, @SerializedName("lang") val lang: String? = null, @SerializedName("lang_code") val langCode: String? = null)
data class StreamlyStremioStream(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("externalUrl") val externalUrl: String? = null,
    @SerializedName("ytId") val ytId: String? = null,
    @SerializedName("infoHash") val infoHash: String? = null,
    @SerializedName("fileIdx") val fileIdx: Int? = null,
    @SerializedName("sources") val sources: List<String> = emptyList(),
    @SerializedName("behaviorHints") val behaviorHints: StreamlyStremioBehaviorHints? = null,
    @SerializedName("subtitles") val subtitles: List<StreamlyStremioSubtitle> = emptyList()
)
