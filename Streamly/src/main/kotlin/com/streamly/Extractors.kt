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

/** Premium file lockers: no extractor can pull streams from their file pages
 *  (login/API only) — routing them just burns timeouts. Observed 0/15+ via
 *  loadExtractor across runs. */
internal fun isDeadLocker(url: String): Boolean {
    val h = url.lowercase()
    return listOf(
        "nitroflare.com", "rapidgator.net", "1fichier.com", "frdl.io",
        "1cloudfile.com", "ddownload.com", "mdiaload.com",
    ).any { it in h }
}

/**
 * Drops redundant `master.m3u8` links: when a master sits in the same
 * directory as its own rendition playlists (e.g. Luluvdo
 * `..._h/index-v1-a1.m3u8` + `..._h/master.m3u8`), the player list shows the
 * same quality twice. Keeps lone masters and masters covering other
 * directories (true multi-rendition urlsets).
 */
internal fun dropRedundantMasters(links: List<ExtractorLink>): List<ExtractorLink> {
    if (links.size < 2) return links
    fun isMaster(l: ExtractorLink): Boolean {
        if (l.type != ExtractorLinkType.M3U8) return false
        // Wish-family hosts serve playlists as .txt (upstream intercepts
        // `txt|m3u8`); treat both as masters.
        val file = l.url.substringBefore("?").substringAfterLast("/")
        return file.equals("master.m3u8", ignoreCase = true) ||
            file.equals("master.txt", ignoreCase = true)
    }
    fun dirOf(l: ExtractorLink): String = l.url.substringBefore("?").substringBeforeLast("/")
    val hasNonMasterDir = links.filterNot(::isMaster).map(::dirOf).toSet()
    if (hasNonMasterDir.isEmpty()) return links
    return links.filter { l -> !isMaster(l) || dirOf(l) !in hasNonMasterDir }
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
        dropRedundantMasters(variants).forEach(callback)
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

/** Dood family (d0o0d.com and rotations). Per-mirror subclasses pin mainUrl
 *  so the playback Referer stays same-host (upstream DoodExtractor design);
 *  d0o0d.com itself 301s to playmogo.com. */
class Dooood : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://playmogo.com"
}

class DoodPlaymogo : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://playmogo.com"
}

class DoodDsvplay : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://dsvplay.com"
}

class DoodDs2play : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://ds2play.com"
}

class DoodStreamCom : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://doodstream.com"
}

/** True when [host] belongs to the DoodStream mirror family (upstream
 *  DoodExtractor mirror list + observed rotations). */
private fun isDoodHost(host: String): Boolean {
    if ("dood" in host || "d0o0d" in host || "do0od" in host || "d000d" in host ||
        "playmogo" in host || "dsvplay" in host || "ds2play" in host || "ds2video" in host ||
        "doods.pro" in host || "vide0.net" in host || "myvidplay" in host
    ) return true
    // dood.wf/cx/sh/watch/pm/to/so/ws/yt/li
    return Regex("""dood\.(wf|cx|sh|watch|pm|to|so|ws|yt|li)""").containsMatchIn(host)
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

/** hgcloud.to is StreamWish-family (upstream Hgcloudto subclass): the packed
 *  JWPlayer parse (plus upstream's own WebView fallback) replaces the custom
 *  hidden-WebView sniff when it works. Same-host mainUrl for headers. */
class Hgcloud : StreamWishExtractor() {
    override val name = "Hgcloud"
    override val mainUrl = "https://hgcloud.to"
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
        // Subtitles still flow via subtitleCallback untouched, except the
        // StreamWish placeholder ("Upload captions" -> empty.srt): an empty
        // file on videos that already carry hardcoded Arabic subs.
        val buffered = ArrayList<ExtractorLink>()
        val out: (ExtractorLink) -> Unit = { l -> buffered.add(l) }
        val subOut: (SubtitleFile) -> Unit = { s ->
            if (s.url.contains("empty.srt", ignoreCase = true)) {
                Log.d(TAG, "[route  ] skip placeholder subtitle ${s.url}")
            } else {
                subtitleCallback(s)
            }
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
                "lulu" in host || "fastvip" in host || "streamwish" in host || "strwish" in host || "wish" in host -> "Luluvdo"
                "hgcloud" in host -> "Hgcloud"
                "dood" in host || "d0o0d" in host || "do0od" in host || "d000d" in host || "playmogo" in host || "dsvplay" in host || "ds2play" in host -> "Dood"
                "mixdrop" in host || "mxdrop" in host -> "MixDrop"
                "uqload" in host -> "Uqload"
                "streamtape" in host -> "Streamtape"
                isDoodHost(host) -> "Dood"
                else -> "loadExtractor"
            }
            // MixDrop file pages (/f/<id>) carry no embed: use the /e/ player.
            val routedLink = if (("mixdrop" in host || "mxdrop" in host) && "/f/" in host) {
                link.replace("/f/", "/e/")
            } else link
            when {
                "vidtube" in host -> Vidtube().getUrl(routedLink, referer, subOut, out)
                "updown" in host -> UpDown().getUrl(routedLink, referer, subOut, out)
                "anafast" in host -> AnaFast().getUrl(routedLink, referer, subOut, out)
                "vidspeed" in host -> VidSpeed().getUrl(routedLink, referer, subOut, out)
                "cdnplus" in host -> CdnPlus().getUrl(routedLink, referer, subOut, out)
                "mp4plus" in host -> Mp4Plus().getUrl(routedLink, referer, subOut, out)
                "filelion" in host -> Filelion().getUrl(routedLink, referer, subOut, out)
                "lulu" in host || "fastvip" in host || "streamwish" in host || "strwish" in host || "wish" in host -> Luluvdo().getUrl(routedLink, referer, subOut, out)
                "hgcloud" in host -> Hgcloud().getUrl(routedLink, referer, subOut, out)
                // Dood mirrors pin same-host mainUrl for playback Referer
                // (upstream DoodExtractor design).
                "playmogo" in host -> DoodPlaymogo().getUrl(routedLink, referer, subOut, out)
                "dsvplay" in host -> DoodDsvplay().getUrl(routedLink, referer, subOut, out)
                "ds2play" in host -> DoodDs2play().getUrl(routedLink, referer, subOut, out)
                "doodstream" in host -> DoodStreamCom().getUrl(routedLink, referer, subOut, out)
                isDoodHost(host) -> Dooood().getUrl(routedLink, referer, subOut, out)
                "mixdrop" in host || "mxdrop" in host -> MixDropPs().getUrl(routedLink, referer, subOut, out)
                "uqload" in host -> Uqload().getUrl(routedLink, referer, subOut, out)
                "streamtape" in host -> StreamTape().getUrl(routedLink, referer, subOut, out)
                else -> {
                    loadExtractor(routedLink, referer, subOut, out)
                }
            }
            var emittedN = 0
            dropRedundantMasters(buffered).forEach { l ->
                emittedN++
                callback(relabelLink(l, providerName))
            }
            Log.d(TAG, "[route  ] $host -> $extractorName emitted=$emittedN (raw=${buffered.size})")
        } catch (e: Exception) {
            Log.e(TAG, "[route  ] Failed to extract $link: ${e.message}")
        }
    }
}
