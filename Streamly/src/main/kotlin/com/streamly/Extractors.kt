package com.streamly

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rebuilds [link] with the provider name prefixed so players show e.g.
 * "TopCinema - Vidtube". Passthrough when blank or already labeled.
 */
fun relabelLink(link: ExtractorLink, providerName: String?): ExtractorLink {
    if (providerName.isNullOrBlank() || link.name.startsWith("$providerName ")) return link
    return runCatching {
        @Suppress("DEPRECATION")
        ExtractorLink(
            source = link.source,
            name = "$providerName - ${link.name}",
            url = link.url,
            referer = link.referer,
            quality = link.quality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
        )
    }.getOrElse { link }
}

/**
 * Base for hosts serving Dean-Edwards packed JWPlayer pages where the source
 * lives in a `file: "..."` key after unpacking (VideoTube, UpDown, ...).
 * Handles both m3u8 playlists and direct mp4 files.
 */
open class PackedJwPlayer : ExtractorApi() {
    override val name = "PackedJW"
    override val mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val text = app.get(url, referer = referer).text
        val unpacked = if (getPacked(text).isNullOrEmpty()) text else getAndUnpack(text)

        val sources = Regex("""file\s*:\s*["']([^"']+)["']""").findAll(unpacked)
            .mapNotNull { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
            .ifEmpty {
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(unpacked + text)
                    .mapNotNull { it.groupValues[1] }
                    .distinct()
                    .toList()
            }

        // Hosts like Vidtube 403 their stream CDN unless requests carry the
        // embed host as Referer, so it rides along in the headers map; the
        // player uses it for playback too. One adaptive link per source — no
        // quality list; ExoPlayer adapts from the master playlist itself.
        val hlsHeaders = mapOf("Referer" to mainUrl)

        var emitted = 0
        sources.forEach { src ->
            if (src.contains(".m3u8")) {
                callback(m3u8Link(src, hlsHeaders))
                emitted++
            } else {
                callback(fileLink(src, referer, getQualityFromName(src)))
                emitted++
            }
        }

        // Last resort: any direct file link in the page
        if (emitted == 0) {
            Regex("""(https?://[^"'\s]+?\.(?:m3u8|mp4)[^"'\s]*)""").findAll(unpacked)
                .mapNotNull { it.groupValues[1] }
                .forEach { src ->
                    if (src.contains(".m3u8")) {
                        callback(m3u8Link(src))
                    } else {
                        callback(
                            newExtractorLink(name, name, url = src) {
                                this.referer = referer ?: mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    emitted++
                }
        }
    }

    private suspend fun fileLink(src: String, referer: String?, quality: Int): ExtractorLink =
        newExtractorLink(name, name, url = src) {
            this.referer = referer ?: mainUrl
            this.quality = quality
            this.type = ExtractorLinkType.VIDEO
        }

    // The master-playlist fallback must present the host's own referer: CDNs
    // like Vidtube's reject cross-site (site-of-origin) referers with 403.
    // Single adaptive link (no quality expansion) with playback headers.
    private suspend fun m3u8Link(src: String, headers: Map<String, String> = emptyMap()): ExtractorLink =
        newExtractorLink(name, name, url = src) {
            this.referer = mainUrl
            this.quality = Qualities.Unknown.value
            this.type = ExtractorLinkType.M3U8
            if (headers.isNotEmpty()) this.headers = headers
        }
}

/** VideoTube (down.vidtube.one/embed-*.html) */
class Vidtube : PackedJwPlayer() {
    override val name = "Vidtube"
    override val mainUrl = "https://down.vidtube.one"
}

/** UpDown (updown.icu / updown.cam / embed-*-*.html) — packed JWPlayer, direct mp4 */
class UpDown : PackedJwPlayer() {
    override val name = "UpDown"
    override val mainUrl = "https://updown.icu"
}

/** Dood family (d0o0d.com and rotations) */
class Dooood : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://d0o0d.com"
}

/** MixDrop (mixdrop.ps and rotations) */
class MixDropPs : MixDrop() {
    override var mainUrl = "https://mixdrop.ps"
}

/** Filelions family */
class Filelion : Filesim() {
    override val name = "Filelion"
    override val mainUrl = "https://filelions.to"
}

/** LuluStream family */
class Luluvdo : StreamWishExtractor() {
    override val name = "Luluvdo"
    override val mainUrl = "https://luluvdo.com"
}

/** Uqload (uqload.is and rotations) */
class Uqload : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var response = app.get(url.replace("/download/", "/e/"), referer = referer)
        val iframe = response.document.selectFirst("iframe")
        if (iframe != null) {
            response = app.get(
                iframe.attr("src"), headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Sec-Fetch-Dest" to "iframe"
                ), referer = response.url
            )
        }

        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        val regex = Regex("""hls2":"(?<hls2>[^"]+)"|hls4":"(?<hls4>[^"]+)"""")
        regex.findAll(script).mapNotNull { matchResult ->
            when {
                matchResult.groups["hls2"] != null -> matchResult.groups["hls2"]!!.value
                matchResult.groups["hls4"] != null -> "$mainUrl${matchResult.groups["hls4"]!!.value}"
                else -> null
            }
        }.toList().forEach { m3u8 ->
            // Single adaptive link — no quality list.
            callback(
                newExtractorLink(name, name, url = m3u8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                },
            )
        }
    }
}

/** Routes an embed iframe URL found on a TopCinema watch page to the right extractor.
 * Host domains rotate frequently (e.g. d0o0d.com / do0od.com / d000d.com), so we match
 * on stable keywords instead of exact domains. */
object EmbedRouter {
    private const val TAG = "EmbedRouter"

    suspend fun route(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        providerName: String? = null,
    ) {
        val host = link.lowercase()
        // Single simple link per embed: built-in extractors fan out into
        // per-quality rows (Strwish 800p, …); keep the first and drop the
        // rest. Subtitles still flow via subtitleCallback untouched.
        var emittedLink = false
        val out: (ExtractorLink) -> Unit = { l ->
            if (!emittedLink) {
                emittedLink = true
                callback(relabelLink(l, providerName))
            }
        }
        try {
            val extractorName = when {
                "vidtube" in host -> "Vidtube"
                "updown" in host -> "UpDown"
                "filelion" in host -> "Filelion"
                "lulu" in host || "fastvip" in host -> "Luluvdo"
                "dood" in host || "d0o0d" in host || "do0od" in host || "d000d" in host || "playmogo" in host -> "Dood"
                "mixdrop" in host || "mxdrop" in host -> "MixDrop"
                "uqload" in host -> "Uqload"
                "streamtape" in host -> "Streamtape"
                else -> "loadExtractor"
            }
            Log.d(TAG, "[route  ] $host -> $extractorName")
            when {
                "vidtube" in host -> Vidtube().getUrl(link, referer, subtitleCallback, out)
                "updown" in host -> UpDown().getUrl(link, referer, subtitleCallback, out)
                "filelion" in host -> Filelion().getUrl(link, referer, subtitleCallback, out)
                "lulu" in host || "fastvip" in host -> Luluvdo().getUrl(link, referer, subtitleCallback, out)
                "dood" in host || "d0o0d" in host || "do0od" in host || "d000d" in host || "playmogo" in host ->
                    Dooood().getUrl(link, referer, subtitleCallback, out)
                "mixdrop" in host || "mxdrop" in host -> MixDropPs().getUrl(link, referer, subtitleCallback, out)
                "uqload" in host -> Uqload().getUrl(link, referer, subtitleCallback, out)
                "streamtape" in host -> StreamTape().getUrl(link, referer, subtitleCallback, out)
                else -> {
                    loadExtractor(link, referer, subtitleCallback, out)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[route  ] Failed to extract $link: ${e.message}")
        }
    }
}
