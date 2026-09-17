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
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rebuilds [link] with the provider name prefixed so players show e.g.
 * "TopCinema - Vidtube 1080p". Passthrough when blank or already labeled.
 *
 * Always-expand: per-quality rows stay distinct — quality/type/headers are
 * preserved untouched so the player offers every variant for slow networks.
 */
fun relabelLink(link: ExtractorLink, providerName: String?): ExtractorLink {
    val baseName = link.name.trim().ifBlank { link.name.trim() }
    if (providerName.isNullOrBlank() || baseName.startsWith("$providerName ")) return link
    return runCatching {
        @Suppress("DEPRECATION")
        ExtractorLink(
            source = link.source,
            name = "$providerName - $baseName",
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
 * Expands a master m3u8 into per-quality variants (1080/720/480/…) so slow
 * networks can pick a lower rendition. Falls back to the single adaptive
 * link when the playlist cannot be fetched/parsed (tokenized hosts, 403s).
 */
private suspend fun emitM3u8Variants(
    sourceName: String,
    m3u8Url: String,
    referer: String,
    headers: Map<String, String> = emptyMap(),
    callback: (ExtractorLink) -> Unit,
) {
    val variants = runCatching {
        if (headers.isEmpty()) generateM3u8(sourceName, m3u8Url, referer)
        else generateM3u8(source = sourceName, streamUrl = m3u8Url, referer = referer, headers = headers)
    }.getOrNull().orEmpty()
    if (variants.isNotEmpty()) {
        variants.forEach(callback)
        return
    }
    callback(
        newExtractorLink(sourceName, sourceName, url = m3u8Url) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.type = ExtractorLinkType.M3U8
            if (headers.isNotEmpty()) this.headers = headers
        }
    )
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
        // player uses it for playback too. Always expand the master into
        // per-quality variants for manual selection on slow networks.
        val hlsHeaders = mapOf("Referer" to mainUrl)

        var emitted = 0
        sources.forEach { src ->
            if (src.contains(".m3u8")) {
                emitM3u8Variants(name, src, mainUrl, hlsHeaders, callback)
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
                        emitM3u8Variants(name, src, mainUrl, hlsHeaders, callback)
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

/** VidSpeed (vidspeed.cyou and rotations) — packed JWPlayer, master m3u8 in `file:` after unpack */
class VidSpeed : PackedJwPlayer() {
    override val name = "VidSpeed"
    override val mainUrl = "https://vidspeed.cyou"
}

/** CdnPlus (cdnplus.space and rotations) — packed JWPlayer, master m3u8 in `file:` after unpack */
class CdnPlus : PackedJwPlayer() {
    override val name = "CdnPlus"
    override val mainUrl = "https://cdnplus.space"
}

/** MP4Plus (mp4plus.cyou / mp4plus.org) — plain JWPlayer setup with labeled direct mp4s */
class Mp4Plus : ExtractorApi() {
    override val name = "Mp4Plus"
    override val mainUrl = "https://mp4plus.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val text = app.get(url, referer = referer).text
        Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']\s*,\s*label\s*:\s*["']([^"']+)["']""")
            .findAll(text)
            .forEach { m ->
                callback(
                    newExtractorLink(name, name, url = m.groupValues[1]) {
                        this.referer = referer ?: mainUrl
                        this.quality = getQualityFromName(m.groupValues[2])
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
            }
    }
}

/** AnaFast (anafast.cyou and rotations) — plain JWPlayer setup with a
 * tokenized master m3u8 in `sources: [{file: "..."}]`. No packing, no DRM. */
class AnaFast : ExtractorApi() {
    override val name = "AnaFast"
    override val mainUrl = "https://anafast.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val text = app.get(url, referer = referer).text
        val src = Regex("""sources\s*:\s*\[\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(text)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(text)?.groupValues?.get(1)
            ?: return
        // Tokenized master (?t=&s=&e=): expand into per-quality variants.
        val ref = referer ?: mainUrl
        emitM3u8Variants(
            name, src, ref,
            mapOf("Referer" to ref),
            callback,
        )
    }
}

/** Uqload (uqload.is and rotations) */
class Uqload : ExtractorApi() {    override val name = "Uqload"
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
            // Expand each playlist into per-quality variants.
            emitM3u8Variants(
                name, m3u8, mainUrl,
                mapOf("Referer" to mainUrl),
                callback,
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
        // Always-expand: forward every variant built-in extractors emit
        // (Strwish 1080p/720p/…) so slow networks can pick a lower rendition.
        // Subtitles still flow via subtitleCallback untouched.
        val out: (ExtractorLink) -> Unit = { l ->
            callback(relabelLink(l, providerName))
        }
        try {
            val extractorName = when {
                "vidtube" in host -> "Vidtube"
                "updown" in host -> "UpDown"
                "anafast" in host -> "AnaFast"
                "vidspeed" in host -> "VidSpeed"
                "cdnplus" in host -> "CdnPlus"
                "mp4plus" in host -> "Mp4Plus"
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
                "anafast" in host -> AnaFast().getUrl(link, referer, subtitleCallback, out)
                "vidspeed" in host -> VidSpeed().getUrl(link, referer, subtitleCallback, out)
                "cdnplus" in host -> CdnPlus().getUrl(link, referer, subtitleCallback, out)
                "mp4plus" in host -> Mp4Plus().getUrl(link, referer, subtitleCallback, out)
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
