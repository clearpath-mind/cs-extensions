package com.streamly

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Request as OkRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// Link sources. Each block below implements one streaming site as a link
// source for Streamly, following the same shape: search the site by TMDB
// title -> fuzzy-match the Latin part of the Arabic slug to pick the right
// post -> resolve season/episode structurally -> route every embed/direct
// link through EmbedRouter.
//
// Currently: EgyWatch, CimaCloud, DramaWorld, TimeMovies. Every master m3u8
// is expanded into per-quality variants so slow networks can pick manually.
/// ---------------------------------------------------------------------------

private const val RESOLVER_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

// Default header origin for the shared WebView resolver below (used by
// the remaining WebView-routed sources, e.g. EgyDead).
internal const val RESOLVER_DEFAULT_ORIGIN = "https://www.fasel-hd.co"

fun extractIframeSources(doc: Document, base: String): List<String> {
    val results = LinkedHashSet<String>()
    val blocked = listOf(
        "google.com/recaptcha",
        "google.com/ads",
        "googlesyndication.com",
        "googletagmanager.com",
    )
    fun add(url: String) {
        val fixed = fixUrl(url, base)
        if (blocked.none { fixed.contains(it) }) results.add(fixed)
    }
    doc.select("iframe[src]").forEach { el ->
        el.attr("src").takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val onClickRegex = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
    doc.select("[onclick]").forEach { el ->
        onClickRegex.find(el.attr("onclick"))?.let { add(it.groupValues[1]) }
    }
    val scriptRegex = Regex("""https?://[^\s"'<>]+""")
    doc.select("script").forEach { s ->
        val data = s.data()
        if (data.isNotBlank()) scriptRegex.findAll(data).forEach { m ->
            val u = m.value
            if (u.contains("player") || u.contains("embed")) add(u)
        }
    }
    // Share short-links (div.shortLink / span#liskSh, e.g. `?p=309212`) point at
    // the post page itself and can never yield a stream — only accept
    // player-like URLs here, or a dead page burns a full WebView cycle.
    doc.select("div.shortLink, span#liskSh, a[data-src]").forEach { el ->
        el.text().trim().takeIf { it.startsWith("http") }
            ?.takeIf { u -> u.contains("player", ignoreCase = true) || u.contains("embed", ignoreCase = true) || u.contains("video", ignoreCase = true) }
            ?.let { add(it) }
    }
    return results.toList()
}

@SuppressLint("SetJavaScriptEnabled")
suspend fun sharedWebViewResolve(
    iframeUrl: String,
    referer: String,
    sniffMp4: Boolean = false,
    onResolvedPage: (String) -> Unit = {},
): String? =
    suspendCancellableCoroutine { cont ->
        val activity = StreamlyRuntime.context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val finalUrl = iframeUrl.replace("&amp;", "&").trim()
        val originalHost = runCatching { Uri.parse(finalUrl).host?.replace("www.", "") ?: "" }.getOrDefault("")
        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                attributes = attributes?.apply { width = 1; height = 1; x = -10000; y = -10000; gravity = Gravity.START or Gravity.TOP }
            }
            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }
            try { dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1)); dialog.show() } catch (e: Exception) {
                try { (activity.window?.decorView as? ViewGroup)?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP)) } catch (_: Exception) {}
            }
            webView.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                allowContentAccess = true; allowFileAccess = true; allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true; javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true); mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true; useWideViewPort = true; builtInZoomControls = true
                displayZoomControls = false; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT; userAgentString = effectiveUa(); blockNetworkImage = true
            }
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val cookieManager = CookieManager.getInstance()
            try { cookieManager.setAcceptCookie(true); cookieManager.setAcceptThirdPartyCookies(webView, true); cookieManager.flush() } catch (_: Exception) {}
            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .build()
            val mainUrlForHeader = RESOLVER_DEFAULT_ORIGIN
            val foundM3u8 = LinkedHashSet<String>()
            val foundMp4 = LinkedHashSet<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())
            var finishRunnable: Runnable? = null
            var attemptTimeoutRunnable: Runnable? = null
            var autoTouchRunnable: Runnable? = null
            var currentAttempt = 0
            val maxAttempts = 2
            val attemptTimeoutMs = 12_000L
            fun cleanup() {
                try { attemptTimeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { autoTouchRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
                try { cookieManager.flush() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }
            fun safeFinish(result: String?) {
                synchronized(finishLock) { if (finished) return; finished = true }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) {}
                cleanup()
            }
            fun chooseAndFinish() {
                if (foundM3u8.isEmpty() && (!sniffMp4 || foundMp4.isEmpty())) { safeFinish(null); return }
                val strict = foundM3u8.firstOrNull { val c = it.substringBefore("?"); c.endsWith(".m3u8") && (c.contains("master") || c.contains("playlist") || c.contains("index")) } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                safeFinish(strict ?: foundM3u8.firstOrNull() ?: foundMp4.first())
            }
            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) {
                    // Optional mp4 fallback (hgcloud serves progressive files).
                    if (sniffMp4 && clean.endsWith(".mp4")) {
                        synchronized(foundMp4) {
                            if (foundMp4.add(url) && finishRunnable == null) {
                                finishRunnable = Runnable { chooseAndFinish() }; handler.postDelayed(finishRunnable!!, 4000)
                            }
                        }
                    }
                    return
                }
                synchronized(foundM3u8) {
                    if (!foundM3u8.contains(url)) {
                        foundM3u8.add(url)
                        finishRunnable?.let { handler.removeCallbacks(it) }
                        if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) {
                            finishRunnable = Runnable { chooseAndFinish() }; handler.postDelayed(finishRunnable!!, 300)
                        } else if (finishRunnable == null) { finishRunnable = Runnable { chooseAndFinish() }; handler.postDelayed(finishRunnable!!, 1500) }
                    }
                }
            }
            fun startNextAttempt() {
                synchronized(finishLock) { if (finished) return }
                if (currentAttempt >= maxAttempts) { chooseAndFinish(); return }
                attemptTimeoutRunnable?.let { handler.removeCallbacks(it) }
                attemptTimeoutRunnable = Runnable {
                    synchronized(foundM3u8) {
                        val empty = foundM3u8.isEmpty() && (!sniffMp4 || foundMp4.isEmpty())
                        if (empty) { currentAttempt++; startNextAttempt() } else chooseAndFinish()
                    }
                }
                handler.postDelayed(attemptTimeoutRunnable!!, attemptTimeoutMs)
                activity.runOnUiThread { try { webView.loadUrl(finalUrl, mapOf("Referer" to referer)) } catch (_: Exception) {} }
            }
            fun getStrategyJs(attempt: Int): String = """
                (function() {
                    const strategy = $attempt;
                    Object.defineProperty(navigator, 'userActivation', { get: () => ({ hasBeenActive: true, isActive: true }) });
                    const Decryptor = { key1: "V2@%YSU2B]G~", key2: "bv0fim4qf17", ie: function(c) { const x = c.charCodeAt(0); if(x>=97 && x<=122) return x-97; if(x>=65 && x<=90) return x-65+26; if(x>=48 && x<=57) return x-48+52; if(x===43) return 62; if(x===47) return 63; return 0; }, bn: function(x) { if(x<=25) return String.fromCharCode(x+97); if(x<=51) return String.fromCharCode(x-26+65); if(x<=61) return String.fromCharCode(x-52+48); if(x===62) return '+'; if(x===63) return '/'; return ' '; }, dec: function(e, k) { let r=''; for(let i=0; i<e.length; i++) { const kc=k[i%(k.length-1)]; const M=this.ie(e[i])-this.ie(kc); r+=this.bn(M<0?M+64:M); } return r; }, parse: function(url) { if(!url || !url.startsWith('enc:')) return url; try { return this.dec(this.dec(url.substring(4), this.key2), this.key1); } catch(e){return url;} } };
                    if ((strategy === 0 || strategy === 4) && !window.__isDecryptionHooked) { window.__isDecryptionHooked = true; let chk = setInterval(function() { if (typeof window.jwplayer === 'function' && !window.jwplayer.__hooked) { const orig = window.jwplayer; window.jwplayer = function() { const p = orig.apply(this, arguments); if (!p.__sHook) { p.__sHook = true; const oSetup = p.setup; p.setup = function(cfg) { try { let s = cfg.sources || (cfg.playlist && cfg.playlist[0] ? cfg.playlist[0].sources : []); if (s) s.forEach(x => { if (x.file && x.file.startsWith('enc:')) x.file = Decryptor.parse(x.file); }); } catch(e){} cfg.autostart = true; cfg.mute = true; return oSetup.call(this, cfg); }; } return p; }; Object.assign(window.jwplayer, orig); window.jwplayer.prototype = orig.prototype; window.jwplayer.__hooked = true; clearInterval(chk); } }, 10); }
                    try { let p = typeof window.jwplayer === 'function' ? window.jwplayer("player") : null; let isPlaying = p && (p.getState() === 'playing' || p.getState() === 'buffering'); if (isPlaying) return; if (strategy === 0 || strategy === 1 || strategy === 7) { var els = document.querySelectorAll('button, a, [onclick], video, [role="button"], .jw-icon, .vjs-control, .po-play-btn, .plyr__control'); els.forEach(el => { if (el.href && (el.href.includes('google.com') || el.href.includes('recaptcha'))) return; try { el.click(); } catch(e){} }); } if (strategy === 2 || strategy === 7) { if (p && typeof p.play === 'function') { p.setMute(true); p.play(); } } } catch(e) {}
                })();
            """.trimIndent()
            val fastSnifferJs = """
            (function() {
                try { window.open = function() { return null; }; if (!window.__NET_HOOKED__) { window.__NET_HOOKED__ = true; const _fetch = window.fetch; if (_fetch) { window.fetch = function() { return _fetch.apply(this, arguments).then(function(resp) { try { const u = resp && resp.url ? resp.url : ''; if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); } } catch(e){} return resp; }); }; } const _open = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function(method, u) { this.addEventListener('load', function() { try { if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); } } catch(e){} }); return _open.apply(this, arguments); }; } } catch(err){}
            })();
            """.trimIndent()
            val webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url.toString(); val lowerUrl = url.lowercase()
                    if (!lowerUrl.startsWith("http")) return true
                    if (lowerUrl.contains("policies.google.com") || lowerUrl.contains("recaptcha") || lowerUrl.contains("mcaptcha") || lowerUrl.contains("melbet")) { Handler(Looper.getMainLooper()).post { view?.loadUrl(finalUrl, mapOf("Referer" to referer)) }; return true }
                    val currentHost = runCatching { Uri.parse(url).host?.replace("www.", "") ?: "" }.getOrDefault("")
                    if (originalHost.isNotBlank() && currentHost.isNotBlank() && !currentHost.contains(originalHost)) {
                        // hgcloud loader mode: the JS loader hops to rotating
                        // player hosts before any stream exists — follow
                        // main-frame hops so the sniffer sees the player page.
                        // The view is hidden/untouchable and the attempt is
                        // time-bounded, so ad hops are harmless.
                        if (sniffMp4 && request?.isForMainFrame == true && !request.hasGesture()) {
                            Log.d("StreamHG", "following loader redirect: $url")
                            return false
                        }
                        val samePlayerRedirect = sniffMp4 && request?.isForMainFrame == true &&
                            !request.hasGesture() && Uri.parse(url).path == Uri.parse(finalUrl).path
                        if (!samePlayerRedirect) return true
                        Log.d("StreamHG", "following player redirect: $url")
                    }
                    return false
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { super.onPageStarted(view, url, favicon); view?.evaluateJavascript(fastSnifferJs, null) }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url); view?.evaluateJavascript(fastSnifferJs, null)
                    autoTouchRunnable?.let { handler.removeCallbacks(it) }
                    autoTouchRunnable = object : Runnable { override fun run() { if (finished) return; view?.evaluateJavascript(getStrategyJs(currentAttempt), null); handler.postDelayed(this, 1000) } }
                    handler.postDelayed(autoTouchRunnable!!, 500)
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val method = request.method
                    val lower = url.lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".woff2") || lower.endsWith(".css")) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (method.equals("GET", ignoreCase = true) && lower.contains(".m3u8") && lower.substringBefore("?").endsWith(".m3u8")) {
                        handleFoundLink(url)
                        try {
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", effectiveUa())
                                .header("Referer", referer)
                                .header("Origin", mainUrlForHeader)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                            val response = client.newCall(reqBuilder.build()).execute()
                            if (!response.isSuccessful) return null
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                            return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                        } catch (_: Exception) { return null }
                    }
                    if (sniffMp4 && method.equals("GET", ignoreCase = true) && lower.substringBefore("?").endsWith(".mp4")) {
                        handleFoundLink(url)
                    }
                    if (method.equals("GET", ignoreCase = true) && (lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))) {
                        try {
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", effectiveUa())
                                .header("Referer", referer)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                            val response = client.newCall(reqBuilder.build()).execute()
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "text/html"
                            return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                        } catch (_: Exception) { return super.shouldInterceptRequest(view, request) }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                @SuppressLint("WebViewClientOnReceivedSslError") override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.proceed() }
            }
            webView.webViewClient = webViewClient
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean { val msg = cm?.message() ?: ""; if (msg.startsWith("NET_M3U8::")) handleFoundLink(msg.substringAfter("::").trim()); return true }
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    try { val transport = resultMsg?.obj as? WebView.WebViewTransport; val adWebView = WebView(activity).apply { layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP); visibility = View.INVISIBLE }; adWebView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = effectiveUa() }; try { (activity.window?.decorView as? ViewGroup)?.addView(adWebView) } catch (_: Exception) {}; adWebView.webViewClient = webViewClient; transport?.webView = adWebView; resultMsg?.sendToTarget(); handler.postDelayed({ try { (adWebView.parent as? ViewGroup)?.removeView(adWebView); adWebView.destroy() } catch (e: Exception) {} }, 1000); return true } catch (e: Exception) { return false }
                }
            }
            startNextAttempt()
            cont.invokeOnCancellation { handler.post { safeFinish(null) } }
        }
    }

object ExternalEarnVidsExtractor {
    private const val TAG = "EarnVidsExtractor"
    suspend fun extract(pageUrl: String, mainReferer: String): String? {
        return runCatching {
            val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "en-US,en;q=0.5", "Connection" to "keep-alive")
            headers["Referer"] = if (pageUrl.contains("fdewsdc.sbs", true)) "https://shhahid4u.cam" else mainReferer
            val response = app.get(pageUrl, headers = headers)
            val html = response.text ?: return@runCatching null
            val m3u8Match = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE).find(html)
            if (m3u8Match != null) { var direct = m3u8Match.value.replace("\\/", "/"); if (direct.startsWith("/")) direct = URI(pageUrl).resolve(direct).toString(); return@runCatching direct }
            if (!html.contains("eval(function")) return@runCatching null
            var working = html; var unpacked: String? = null
            repeat(4) { unpacked = unpackPackerSimple(working, pageUrl) ?: return@repeat; if (!unpacked!!.contains("eval(function")) { working = unpacked!!; return@repeat }; working = unpacked!! }
            if (unpacked.isNullOrBlank()) return@runCatching null
            val cleaned = unpacked!!.replace("\\/", "/")
            val match = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(cleaned)
            if (match == null) {
                val hlsInline = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: Regex(""""hls"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                if (!hlsInline.isNullOrBlank()) { var link = hlsInline.replace("\\/", "/"); if (link.startsWith("/")) link = URI(pageUrl).resolve(link).toString(); return@runCatching link }
                return@runCatching null
            }
            val jsonRaw = match.groupValues[1].replace("'", "\"")
            val map = mutableMapOf<String, String>()
            try { val jo = JSONObject(jsonRaw); val keys = jo.keys(); while (keys.hasNext()) { val k = keys.next(); runCatching { map[k] = jo.getString(k) } } } catch (_: Exception) { for (m in Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(jsonRaw)) { map[m.groupValues[1]] = m.groupValues[2] } }
            var link = map["hls4"] ?: map["hls"] ?: return@runCatching null; link = link.replace("\\/", "/"); if (link.startsWith("/")) link = URI(pageUrl).resolve(link).toString(); link
        }.getOrNull()
    }
    private fun unpackPackerSimple(js: String, pageUrl: String): String? {
        return runCatching {
            val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(js) ?: return null
            val (payloadRaw, radixStr, sympipe) = match.destructured
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")
            val payload = payloadRaw.replace("location.href", "'$pageUrl'").replace("location", "'$pageUrl'").replace("document.cookie", "''").replace("window.location", "'$pageUrl'").replace("window", "this")
            val tokenRe = Regex("""\b[0-9a-zA-Z]+\b""")
            tokenRe.replace(payload) { mo -> val tok = mo.value; runCatching { val idx = tok.toInt(radix); if (idx in 0 until symtab.size) symtab[idx] else tok }.getOrDefault(tok) }
        }.getOrNull()
    }
}

/** megamax.me (EgyDead's primary server) is a Vue/Inertia app: the
 *  /iframe/{id} page carries per-quality stream mirrors in its Inertia props
 *  (`streams.data[]` -> mirrors[] {driver, link} on well-known file hosts).
 *  Fetch the partial payload, then route every mirror of every available
 *  quality through EmbedRouter so slow networks can pick a lower rendition. */
object MegaMaxExtractor {
    private const val TAG = "MegaMax"
    private val VERSION_REGEX = Regex(""""version"\s*:\s*"([0-9a-fA-F]{32,})"""")

    private data class Mirror(val driver: String, val link: String)
    private data class Quality(val label: String, val height: Int, val mirrors: List<Mirror>)

    private fun heightOf(label: String, resolution: String): Int {
        Regex("""(\d{3,4})[xX](\d{3,4})""").find(resolution)?.let {
            return it.groupValues[2].toIntOrNull() ?: 0
        }
        if (label.contains("1080", ignoreCase = true)) return 1080
        if (label.contains("720", ignoreCase = true)) return 720
        if (label.contains("480", ignoreCase = true)) return 480
        if (label.contains("360", ignoreCase = true)) return 360
        if (label.contains("source", ignoreCase = true)) return 9999
        return 0
    }

    private fun megaQuality(height: Int): Int = when {
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun tagMegaQuality(link: ExtractorLink, label: String, height: Int): ExtractorLink {
        // Quality travels in the numeric field only (player badge). Never bake
        // the page label into the name: labels like "SOURCE" or misparsed
        // heights ("804p") otherwise duplicate/clash with the badge.
        val mapped = megaQuality(height)
        val wantQuality =
            if (link.quality == Qualities.Unknown.value && mapped != Qualities.Unknown.value) mapped
            else link.quality
        if (wantQuality == link.quality) return link
        return runCatching {
            @Suppress("DEPRECATION")
            com.lagradost.cloudstream3.utils.ExtractorLink(
                source = link.source,
                name = link.name,
                url = link.url,
                referer = link.referer,
                quality = wantQuality,
                headers = link.headers,
                extractorData = link.extractorData,
                type = link.type,
            )
        }.getOrElse { link }
    }

    suspend fun extract(
        pageUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        providerName: String = "EgyDead",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val iframeUrl = pageUrl.replace("/download/", "/iframe/")
            val headers = mapOf(
                "User-Agent" to effectiveUa(),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
            )
            val html: String? = app.get(iframeUrl, headers = headers, referer = referer, timeout = 15000).text
            if (html.isNullOrBlank()) return@withContext false
            val version = VERSION_REGEX.find(html)?.groupValues?.get(1)
            if (version.isNullOrBlank()) {
                Log.d(TAG, "[mega] no inertia version for $iframeUrl")
                return@withContext false
            }
            val partial: String? = app.get(
                iframeUrl,
                referer = iframeUrl,
                headers = headers + mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                timeout = 15000,
            ).text
            if (partial.isNullOrBlank()) return@withContext false
            val qualities = ArrayList<Quality>()
            try {
                val data = JSONObject(partial).optJSONObject("props")
                    ?.optJSONObject("streams")?.optJSONArray("data")
                    ?: return@withContext false
                for (i in 0 until data.length()) {
                    val q = data.optJSONObject(i) ?: continue
                    val marr = q.optJSONArray("mirrors") ?: continue
                    val mirrors = ArrayList<Mirror>()
                    for (j in 0 until marr.length()) {
                        val m = marr.optJSONObject(j) ?: continue
                        var link = m.optString("link").trim()
                        if (link.startsWith("//")) link = "https:$link"
                        if (!link.startsWith("http")) continue
                        mirrors.add(Mirror(m.optString("driver"), link))
                    }
                    if (mirrors.isEmpty()) continue
                    val label = q.optString("label")
                    qualities.add(Quality(label, heightOf(label, q.optString("resolution")), mirrors))
                }
            } catch (e: Exception) {
                Log.d(TAG, "[mega] parse failed: ${e.message}")
                return@withContext false
            }
            if (qualities.isEmpty()) {
                Log.d(TAG, "[mega] no qualities for $iframeUrl")
                return@withContext false
            }
            qualities.sortByDescending { it.height }
            Log.d(TAG, "[mega] qualities=${qualities.map { it.label }}")
            var emittedTotal = 0
            for (quality in qualities) {
                for (mirror in quality.mirrors) {
                    var n = 0
                    val counting: (ExtractorLink) -> Unit = { link ->
                        n++; emittedTotal++
                        callback(tagMegaQuality(link, quality.label, quality.height))
                    }
                    Log.d(TAG, "[mega] ${quality.label} driver=${mirror.driver} link=${mirror.link}")
                    runCatching {
                        EmbedRouter.route(mirror.link, iframeUrl, subtitleCallback, counting, providerName)
                    }
                    Log.d(TAG, "[mega] driver=${mirror.driver} emitted=$n")
                }
            }
            emittedTotal > 0
        } catch (e: Exception) {
            Log.e(TAG, "[mega] failed: ${e.message}")
            false
        }
    }
}
// ---------------------------------------------------------------------------
// EgyWatch (EasyPlex app API). Search hits carry tmdb_id, so matching is
// exact against LinkData.id with a fuzzy-title fallback — no slug scraping.
// The {code} path segment is not validated server-side (verified: any value
// returns 200 with real data), and the live base comes from a public mirror
// config (client.egywatch.live/api_urls.json). Video mirrors route through
// EmbedRouter like every other Arabic source.
// ---------------------------------------------------------------------------

private const val EGYWATCH_API_URLS = "https://client.egywatch.live/api_urls.json"
private const val EGYWATCH_DEFAULT_BASE = "https://gateway.egywatch.live/egywatchapp/public/api"
private const val EGYWATCH_CODE = "android"
private const val EGYWATCH_TAG = "EgyWatch"

private val EGYWATCH_HEADERS = mapOf(
    "User-Agent" to "EasyPlex/5.0.0 (Android)",
    "packagename" to "com.linkletter.app",
    "Accept" to "application/json",
    "x-app-id" to "Egywatch-mobile",
    "x-platform" to "android",
)

/** Candidate API bases: live mirror config first, hardcoded fallbacks after. */
private suspend fun egyWatchBases(): List<String> {
    val live = runCatching {
        JSONObject(app.get(EGYWATCH_API_URLS, timeout = 10000).text)
            .optString("EgywatchV5").trim().removeSuffix("/")
    }.getOrNull().orEmpty()
    return (listOf(
        live,
        EGYWATCH_DEFAULT_BASE,
        "https://gateway.egywatch.live/egywatch/public/api",
        "https://rn62mwg.com/egywatchapp/public/api",
    ).map { it.trim().removeSuffix("/") }
        .filter { it.startsWith("http") }
        .distinct())
}

/** GET a JSON endpoint, trying each base in order. Returns working base + body. */
private suspend fun egyWatchGet(bases: List<String>, path: String): Pair<String, JSONObject>? {
    for (base in bases) {
        val json = runCatching {
            JSONObject(app.get("$base$path", headers = EGYWATCH_HEADERS, timeout = 15000).text)
        }.getOrNull()
        if (json != null) return base to json
    }
    return null
}

/** Pick the API item id: exact tmdb_id match first, fuzzy title fallback (>= 60). */
private fun egyWatchPickId(search: JSONObject, res: LinkData): Pair<Int, String>? {
    val arr = search.optJSONArray("search") ?: return null
    val wantSeries = !res.isMovie
    var fuzzyId = -1
    var fuzzyType = ""
    var fuzzyScore = 0
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optInt("id", -1)
        if (id <= 0) continue
        val type = o.optString("type")
        // Blank type: assume it matches rather than discarding the hit.
        val isSeries = if (type.isBlank()) wantSeries else !type.equals("movie", ignoreCase = true)
        if (o.optInt("tmdb_id", -1) == res.id && isSeries == wantSeries) return id to type
        val title = o.optString("title").ifBlank { o.optString("name") }
        val score = FuzzySearch.ratio(res.title?.lowercase().orEmpty(), title.lowercase())
        if (isSeries == wantSeries && score > fuzzyScore) {
            fuzzyScore = score
            fuzzyId = id
            fuzzyType = type
        }
    }
    return if (fuzzyId > 0 && fuzzyScore >= 60) fuzzyId to fuzzyType else null
}

private suspend fun egyWatchRouteVideos(
    base: String,
    videos: org.json.JSONArray,
    subtitleCallback: (SubtitleFile) -> Unit,
    counting: (ExtractorLink) -> Unit,
) {
    (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.amap { v ->
        runCatching {
            val link = v.optString("link").trim()
            if (!link.startsWith("http")) return@runCatching
            val ref = v.optString("header").trim().takeIf { it.startsWith("http") } ?: base
            EmbedRouter.route(link, ref, subtitleCallback, counting, "EgyWatch")
        }
    }
}

suspend fun invokeEgyWatch(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    Log.d(EGYWATCH_TAG, "[invoke] title=$title tmdb=${res.id} movie=${res.isMovie} s=${res.season} e=${res.episode}")
    StreamlyDiag.lastStage = "EgyWatch: start"
    if (title.isEmpty()) return false
    var emitted = 0
    val counting: (ExtractorLink) -> Unit = { emitted++; callback(it) }
    return try {
        val bases = egyWatchBases()
        val encoded = URLEncoder.encode("$title|vide", "UTF-8")
        val searchRes = egyWatchGet(bases, "/search/$encoded/$EGYWATCH_CODE")
        if (searchRes == null) {
            StreamlyDiag.lastStage = "EgyWatch: search failed"
            return false
        }
        val (base, search) = searchRes
        val pick = egyWatchPickId(search, res)
        if (pick == null) {
            Log.d(EGYWATCH_TAG, "[invoke] no match for $title")
            StreamlyDiag.lastStage = "EgyWatch: no match"
            return false
        }
        val (itemId, _) = pick
        StreamlyCache.markEpisodeMatched("egywatch")
        if (res.isMovie) {
            val detail = egyWatchGet(listOf(base), "/media/detail/$itemId/$EGYWATCH_CODE")?.second
            val videos = detail?.optJSONArray("videos")
            if (videos == null) {
                StreamlyDiag.lastStage = "EgyWatch: no videos"
                return false
            }
            egyWatchRouteVideos(base, videos, subtitleCallback, counting)
        } else {
            val show = egyWatchGet(listOf(base), "/series/show/$itemId/$EGYWATCH_CODE")?.second
            val seasons = show?.optJSONArray("seasons")
            if (seasons == null) {
                StreamlyDiag.lastStage = "EgyWatch: no seasons"
                return false
            }
            val seasonId = (0 until seasons.length())
                .mapNotNull { seasons.optJSONObject(it) }
                .firstOrNull { it.optInt("season_number") == (res.season ?: 1) }
                ?.optInt("id", -1) ?: -1
            if (seasonId <= 0) {
                StreamlyDiag.lastStage = "EgyWatch: no season"
                return false
            }
            val season = egyWatchGet(listOf(base), "/series/season/$seasonId/$EGYWATCH_CODE")?.second
            val episodes = season?.optJSONArray("episodes")
            val ep = if (episodes == null) null else (0 until episodes.length())
                .mapNotNull { episodes.optJSONObject(it) }
                .firstOrNull { it.optInt("episode_number") == (res.episode ?: 1) }
            val videos = ep?.optJSONArray("videos")
            if (videos == null) {
                StreamlyDiag.lastStage = "EgyWatch: no episode"
                return false
            }
            egyWatchRouteVideos(base, videos, subtitleCallback, counting)
        }
        Log.d(EGYWATCH_TAG, "[done  ] emitted=$emitted")
        StreamlyDiag.lastStage = if (emitted > 0) "EgyWatch: ok" else "EgyWatch: no links"
        emitted > 0
    } catch (e: Exception) {
        Log.e(EGYWATCH_TAG, "[invoke] failed: ${e.message}")
        StreamlyDiag.lastStage = "EgyWatch: ${e.message}"
        emitted > 0
    }
}
