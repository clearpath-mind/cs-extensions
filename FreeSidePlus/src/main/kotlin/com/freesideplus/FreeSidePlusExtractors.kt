package com.freesideplus

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Same-host mirror subclasses so Byse/Dood embeds resolve even when the
 * host app's bundled extractor registry predates these mirrors.
 *
 * Each tries the normal flow first, then falls back to WebView sniffing
 * (upstream StreamWishExtractor pattern): the plain OkHttp flow dies on
 * these hosts' bot protection while Streamtape passes, which is why only
 * one server showed up.
 */
private val streamSniff = Regex("""mp4|m3u8|txt""")

private suspend fun webViewFallback(
    name: String,
    refererBase: String,
    url: String,
    referer: String?,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val resolver = WebViewResolver(
        interceptUrl = streamSniff,
        additionalUrls = listOf(streamSniff),
        useOkhttp = false,
        timeout = 15_000L,
    )
    val found = runCatching {
        app.get(url, referer = referer, interceptor = resolver).url
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
    return if ("m3u8" in found || found.endsWith(".txt") || "/playlist/" in found) {
        var emitted = false
        runCatching {
            M3u8Helper.generateM3u8(name, found, refererBase).forEach {
                emitted = true
                callback(it)
            }
        }
        emitted
    } else {
        callback(
            newExtractorLink(name, name, found) {
                this.referer = refererBase
                this.quality = Qualities.P1080.value
                this.type = ExtractorLinkType.VIDEO
            }
        )
        true
    }
}

class DoodDsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var emitted = false
        runCatching {
            super.getUrl(url, referer, subtitleCallback) { emitted = true; callback(it) }
        }
        if (!emitted) {
            webViewFallback(name, mainUrl, url, referer, callback)
        }
    }
}

class ByseFsp : ByseSX() {
    override val mainUrl = "https://bysevepoin.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var emitted = false
        runCatching {
            super.getUrl(url, referer, subtitleCallback) { emitted = true; callback(it) }
        }
        if (!emitted) {
            webViewFallback(name, mainUrl, url, referer, callback)
        }
    }
}
