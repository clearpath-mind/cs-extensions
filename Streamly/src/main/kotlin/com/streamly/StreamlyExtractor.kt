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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Request as OkRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
// Currently: TopCinema, WeCima, EgyDead, FaselHD.
// Inlined from previous FaselHdResolver.kt / EarnVidsExtractor.kt to match
// StreamPlay structure (no separate files), as requested.
// ---------------------------------------------------------------------------

private const val FASELHD_RES_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
    doc.select("div.shortLink, span#liskSh, a[data-src]").forEach { el ->
        el.text().trim().takeIf { it.startsWith("http") }?.let { add(it) }
    }
    return results.toList()
}

@SuppressLint("SetJavaScriptEnabled")
suspend fun faselHdResolveWebView(iframeUrl: String, referer: String): String? =
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
                cacheMode = WebSettings.LOAD_DEFAULT; userAgentString = FASELHD_RES_UA; blockNetworkImage = true
            }
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val cookieManager = CookieManager.getInstance()
            try { cookieManager.setAcceptCookie(true); cookieManager.setAcceptThirdPartyCookies(webView, true); cookieManager.flush() } catch (_: Exception) {}
            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .build()
            val mainUrlForHeader = FASELHD_MAIN_URL
            val foundM3u8 = LinkedHashSet<String>()
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
                if (foundM3u8.isEmpty()) { safeFinish(null); return }
                val strict = foundM3u8.firstOrNull { val c = it.substringBefore("?"); c.endsWith(".m3u8") && (c.contains("master") || c.contains("playlist") || c.contains("index")) } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                safeFinish(strict ?: foundM3u8.first())
            }
            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) return
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
                        if (foundM3u8.isEmpty()) { currentAttempt++; startNextAttempt() } else chooseAndFinish()
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
                    if (originalHost.isNotBlank() && currentHost.isNotBlank() && !currentHost.contains(originalHost)) return true
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
                                .header("User-Agent", FASELHD_RES_UA)
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
                    if (method.equals("GET", ignoreCase = true) && (lower.contains("fasel") || lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))) {
                        try {
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", FASELHD_RES_UA)
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
                    try { val transport = resultMsg?.obj as? WebView.WebViewTransport; val adWebView = WebView(activity).apply { layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP); visibility = View.INVISIBLE }; adWebView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = FASELHD_RES_UA }; try { (activity.window?.decorView as? ViewGroup)?.addView(adWebView) } catch (_: Exception) {}; adWebView.webViewClient = webViewClient; transport?.webView = adWebView; resultMsg?.sendToTarget(); handler.postDelayed({ try { (adWebView.parent as? ViewGroup)?.removeView(adWebView); adWebView.destroy() } catch (e: Exception) {} }, 1000); return true } catch (e: Exception) { return false }
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

private const val TOPCINEMA_MAIN_URL = "https://web8.topcinema.cam"
private const val TOPCINEMA_AJAX_SERVER =
    "$TOPCINEMA_MAIN_URL/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
private const val TAG = "TopCinema"

/** TopCinema rotates mirrors; resolve the live origin once and reuse it. */
private suspend fun topcinemaBase(): String = resolveOrigin(TOPCINEMA_MAIN_URL)

private const val MIN_SCORE_MOVIE = 65
private const val MIN_SCORE_SERIES = 60
private const val YEAR_PENALTY = 30

private data class Candidate(
    val url: String,
    val slug: String,
    val latinTitle: String,
    val year: Int?,
)

private val EPISODE_REGEX = Regex("""الحلقة[-\s]*(\d+)""")
private val SEASON_DIGIT_REGEX = Regex("""(?:الموسم|موسم)[-\s]*(\d+)""")
private val LATIN_REGEX = Regex("""[a-zA-Z][a-zA-Z0-9'&.:\-]*|[0-9]+""")
private val YEAR_REGEX = Regex("""(19|20)\d{2}""")

// ---------------------------------------------------------------------------
// Shared CloudFlare-aware network layer
//
// Several sources sit behind Cloudflare's "Verify you are human" challenge.
// CloudStream's built-in CloudflareKiller cannot always clear it, so when we
// detect a challenge page we fall back to a WebView-based solver
// (CloudflareSolver) that simulates the press-and-hold, stores the resulting
// cf_clearance cookie, and lets subsequent same-origin requests pass. The
// solver needs the foreground Activity, which StreamlyProvider / the settings
// fragment publish here before invoking any provider.
// ---------------------------------------------------------------------------

object StreamlyRuntime {
    var context: Context? = null
}

/** Shared diagnostic channel surfaced by the per-source Test UI. */
object StreamlyDiag {
    var lastStage: String = ""
}

private val cfSolveLock = Mutex()
internal const val CF_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private fun cfCookies(url: String): String =
    runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")

private fun isCfChallenge(text: String): Boolean =
    text.contains("just a moment", ignoreCase = true) ||
        text.contains("checking your browser", ignoreCase = true) ||
        text.contains("verify you are human", ignoreCase = true)

/** Solve a CloudFlare challenge via the WebView solver, sharing one lock so
 *  only a single solve runs at a time. Returns the cleared document or null. */
private suspend fun cfSolve(url: String): Document? {
    val activity = StreamlyRuntime.context as? Activity
    if (activity == null) {
        Log.w(TAG, "[cf     ] no Activity context available, skipping WebView solver for $url")
        return null
    }
    return cfSolveLock.withLock { CloudflareSolver.solve(activity, url, CF_UA) }
}

private suspend fun cfGetDoc(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): Document {
    val all = headers.toMutableMap().apply {
        putIfAbsent("User-Agent", CF_UA)
        val c = cfCookies(url)
        if (c.isNotBlank()) putIfAbsent("Cookie", c)
        if (referer != null) put("Referer", referer)
    }
    val doc = try {
        app.get(url, referer = referer, headers = all, timeout = timeout, allowRedirects = true).document
    } catch (e: Exception) {
        Log.w(TAG, "[cfGet  ] $url failed: ${e.message}")
        null
    }
    val html = doc?.toString().orEmpty()
    if (doc != null && !isCfChallenge(html)) return doc
    return cfSolve(url) ?: doc ?: Jsoup.parse("", url)
}

private suspend fun cfGetText(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): String {
    val all = headers.toMutableMap().apply {
        putIfAbsent("User-Agent", CF_UA)
        val c = cfCookies(url)
        if (c.isNotBlank()) putIfAbsent("Cookie", c)
        if (referer != null) put("Referer", referer)
    }
    val text = try {
        app.get(url, referer = referer, headers = all, timeout = timeout, allowRedirects = true).text
    } catch (e: Exception) {
        Log.w(TAG, "[cfGet  ] $url failed: ${e.message}")
        null
    }
    if (text != null && !isCfChallenge(text)) return text
    val solved = cfSolve(url)
    if (solved != null) return solved.toString()
    return text ?: ""
}

private suspend fun cfPostText(
    url: String,
    data: Map<String, String> = emptyMap(),
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): String {
    val all = headers.toMutableMap().apply {
        putIfAbsent("User-Agent", CF_UA)
        val c = cfCookies(url)
        if (c.isNotBlank()) putIfAbsent("Cookie", c)
        if (referer != null) put("Referer", referer)
    }
    val text = try {
        app.post(url, data = data, referer = referer, headers = all, timeout = timeout).text
    } catch (e: Exception) {
        Log.w(TAG, "[cfPost ] $url failed: ${e.message}")
        null
    }
    if (text != null && !isCfChallenge(text)) return text
    // Challenge on a POST: solve, then retry with the freshly stored clearance cookie.
    cfSolve(url)
    val retry = all.toMutableMap().apply {
        val c = cfCookies(url)
        if (c.isNotBlank()) put("Cookie", c)
    }
    return try {
        app.post(url, data = data, referer = referer, headers = retry, timeout = timeout).text
    } catch (e: Exception) {
        text ?: ""
    }
}

private suspend fun cfPostDoc(
    url: String,
    data: Map<String, String> = emptyMap(),
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): Document = Jsoup.parse(cfPostText(url, data, referer, headers, timeout), url)

suspend fun invokeTopCinema(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    Log.d(TAG, "[invoke] title=$title year=${res.year} movie=${res.isMovie} s=${res.season} e=${res.episode}")
    StreamlyDiag.lastStage = "TopCinema: start"
    if (title.isEmpty()) return false

    var emitted = 0
    val countingCallback: (ExtractorLink) -> Unit = { emitted++; callback(it) }

    val ok = if (res.isMovie) {
        resolveMovie(title, res.year, subtitleCallback, countingCallback)
    } else {
        resolveEpisode(
            title,
            res.season ?: 1,
            res.episode ?: 1,
            res.year,
            subtitleCallback,
            countingCallback,
        )
    }
    Log.d(TAG, "[done  ] emitted=$emitted")
    StreamlyDiag.lastStage = if (emitted > 0) "TopCinema: ok" else "TopCinema: no links"
    return ok || emitted > 0
}

// ---------------------------------------------------------------------------
// Search & matching
// ---------------------------------------------------------------------------

private suspend fun searchSite(query: String, type: String): List<Candidate> {
    val direct = searchOnce(query, type)
    if (direct.isNotEmpty() || type == "all") return direct

    // Some posts are not indexed under their section; try unfiltered search
    val fallback = searchOnce(query, "all")
    Log.d(TAG, "[search ] '$type' empty for '$query', fallback 'all' -> ${fallback.size} cards")
    return fallback
}

private suspend fun searchOnce(query: String, type: String): List<Candidate> {
    return withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${topcinemaBase()}/search/?query=$encoded&type=$type"
            val doc = cfGetDoc(url, timeout = 15000)
            doc.select(".Small--Box").mapNotNull { box ->
                val a = box.selectFirst("a[href]") ?: return@mapNotNull null
                val cardUrl = a.absUrl("href").ifEmpty { a.attr("href") }
                if (cardUrl.isBlank()) return@mapNotNull null
                val slug = decodeSlug(cardUrl)
                Candidate(cardUrl, slug, latinTitleFromSlug(slug), yearFromSlug(slug))
            }.filter { it.latinTitle.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "[search ] failed: ${e.message}")
            emptyList()
        }
    }
}

private fun decodeSlug(url: String): String {
    val segment = url.substringBeforeLast('/').substringAfterLast('/')
    return runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}

private fun latinTitleFromSlug(slug: String): String {
    val cleaned = slug
        .replace(Regex("^(?:مشاهدة-)?(?:فيلم|مسلسل|انمي|أنمي|برنامج|عروض)-+"), "")
        .replace(Regex("-+(?:مترجم|مدبلج).*$"), "")
    return LATIN_REGEX.findAll(cleaned)
        .map { it.value.trim('-', ' ', '.') }
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

private fun yearFromSlug(slug: String): Int? =
    YEAR_REGEX.find(slug)?.value?.toIntOrNull()

private fun scoreCandidate(candidate: Candidate, title: String, year: Int?): Int {
    // Sites slugify Latin titles with hyphens ("la-casa-de-papel") while TMDB
    // titles are spaced; normalize both before comparing.
    val candidateTitle = candidate.latinTitle.lowercase().replace('-', ' ')
    var score = FuzzySearch.weightedRatio(candidateTitle, title.lowercase().replace('-', ' '))
    val candidateYear = candidate.year
    if (candidateYear != null && year != null && Math.abs(candidateYear - year) > 1) {
        score -= YEAR_PENALTY
    } else if (candidateYear != null && year != null) {
        score += 5
    }
    return score
}

// ---------------------------------------------------------------------------
// Movie flow
// ---------------------------------------------------------------------------

private suspend fun resolveMovie(
    title: String,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val candidates = searchSite(title, "movies")
    Log.d(TAG, "[search ] movie '$title' -> ${candidates.size} candidates")
    if (candidates.isEmpty()) return false

    val scored = candidates.map { it to scoreCandidate(it, title, year) }
    scored.sortedByDescending { it.second }.take(3).forEach { (c, s) ->
        Log.d(TAG, "[match  ] score=$s latin='${c.latinTitle}' year=${c.year}")
    }

    val best = scored.maxByOrNull { it.second }
        ?.takeIf { it.second >= MIN_SCORE_MOVIE }?.first
    if (best == null) {
        Log.d(TAG, "[match  ] no candidate reached $MIN_SCORE_MOVIE")
        return false
    }

    Log.d(TAG, "[match  ] WINNER ${best.url}")
    val movieUrl = best.url.trimEnd('/')
    val ok = extractServers("$movieUrl/watch/", subtitleCallback, callback)
    val dl = topcinemaDownloadLinks("$movieUrl/download/", subtitleCallback, callback)
    return ok || dl
}

// ---------------------------------------------------------------------------
// Series episode flow
// ---------------------------------------------------------------------------

private suspend fun resolveEpisode(
    title: String,
    season: Int,
    episode: Int,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val candidates = searchSite(title, "series")
    Log.d(TAG, "[search ] series '$title' S$season E$episode -> ${candidates.size} candidates")

    // Site search only returns the latest ~40 episode posts, so never resolve
    // from search results directly. Instead use the best-matching post as an
    // anchor into the show's season navigator, which exposes every season.
    val anchor = candidates.map { it to scoreCandidate(it, title, year) }
        .filter { it.second >= MIN_SCORE_SERIES }
        .maxByOrNull { it.second }?.first
    if (anchor == null) {
        Log.d(TAG, "[match  ] no anchor above $MIN_SCORE_SERIES")
        return false
    }
    Log.d(TAG, "[match  ] anchor ${anchor.url}")

    val doc = try {
        cfGetDoc(anchor.url, timeout = 15000)
    } catch (e: Exception) {
        Log.e(TAG, "[anchor ] failed: ${e.message}")
        return false
    }

    // Preferred path: the season navigator cards link to per-season hubs that
    // list every episode of that season.
    val seasonCards = doc.select("section.allseasonss div.Small--Box.Season")
    if (seasonCards.isNotEmpty()) {
        val card = seasonCards.firstOrNull { c ->
            Regex("""\d+""").find(c.selectFirst(".epnum")?.text().orEmpty())
                ?.value?.toIntOrNull() == season
        }
        if (card == null) {
            Log.d(TAG, "[season ] S$season not among ${seasonCards.size} season cards")
            return false
        }
        val hubUrl = card.selectFirst("a[href]")?.absUrl("href").orEmpty()
        if (!hubUrl.startsWith("http")) return false
        Log.d(TAG, "[season ] hub=$hubUrl")
        val hub = try {
            cfGetDoc(hubUrl, timeout = 15000)
        } catch (e: Exception) {
            Log.e(TAG, "[season ] hub failed: ${e.message}")
            return false
        }
        val epUrl = exactEpisodeUrl(hub, season, episode)
            ?: run {
                // Hubs render only ~50 episodes inline (getMoreByScroll). If the
                // requested one isn't rendered, pull the full list from the
                // theme's Episodes.php endpoint.
                Log.d(TAG, "[match  ] E$episode not on hub inline list, trying AJAX completion")
                val fullList = topcinemaFullSeasonEpisodes(hub)
                    ?: return false
                val url = exactEpisodeUrl(fullList, season, episode)
                if (url == null) Log.d(TAG, "[match  ] E$episode not in full S$season list either")
                url
            } ?: return false
        return topcinemaWithDownload(epUrl, subtitleCallback, callback)
    }

    // Shows without a season navigator (anime etc.): fall back to the episode
    // list rendered on the page itself.
    val epUrl = exactEpisodeUrl(doc, season, episode)
    if (epUrl == null) {
        Log.d(TAG, "[match  ] E$episode not in page episode list")
        return false
    }
    return topcinemaWithDownload(epUrl, subtitleCallback, callback)
}

/**
 * Finds the post URL of exactly episode [episode] inside an episode listing
 * (a season hub or an on-page episode list). If the slug carries an explicit
 * season number it must match [season] too. Returns null when absent — callers
 * fail cleanly instead of guessing.
 */
private fun exactEpisodeUrl(doc: Document, season: Int, episode: Int): String? {
    val anchors = doc.select("section.allepcont a[href]").takeIf { it.isNotEmpty() }
        ?: doc.select("a[href]")
    for (a in anchors) {
        val href = a.absUrl("href").ifEmpty { a.attr("href") }
        if (!href.startsWith("http")) continue
        val slug = decodeSlug(href)
        val ep = EPISODE_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        if (ep != episode) continue
        // The slug's explicit season (if any) must agree with the requested one;
        // otherwise trust the listing scope we were given.
        val slugSeason = SEASON_DIGIT_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull()
        if (slugSeason != null && slugSeason != season) continue
        return href
    }
    return null
}

// ---------------------------------------------------------------------------
// Watch page -> server iframes
// ---------------------------------------------------------------------------

/**
 * Season hubs lazy-load their episode list. This fetches the complete season
 * via the theme's Episodes.php endpoint: it reads the seasons toggler params
 * from the /watch/ page of the first listed episode, then POSTs them.
 * Returns a synthetic document whose anchors carry episode numbers in <em>.
 */
private suspend fun topcinemaFullSeasonEpisodes(hub: Document): Document? {
    val firstEpisode = hub.select("section.allepcont a[href]")
        .firstOrNull()?.absUrl("href")?.takeIf { it.startsWith("http") }
        ?: return null
    return runCatching {
        val watchText = cfGetText("${firstEpisode.trimEnd('/')}/watch/", timeout = 15000)
        val toggler = Jsoup.parse(watchText, firstEpisode)
            .selectFirst(".seasons--toggler a[data-id][data-season]") ?: return null
        val postId = toggler.attr("data-id")
        val seasonId = toggler.attr("data-season")
        if (postId.isBlank() || seasonId.isBlank()) return null
        val ajaxBase = Regex("""MyAjaxURL\s*=\s*["']([^"']+)["']""").find(watchText)
            ?.groupValues?.get(1) ?: topcinemaBase() + "/wp-content/themes/movies2023/Ajaxat"
        val html = cfPostText(
            ajaxBase.trimEnd('/') + "/Single/Episodes.php",
            data = mapOf("season" to seasonId, "post_id" to postId),
            headers = mapOf(
                "Referer" to TOPCINEMA_MAIN_URL,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            timeout = 15000,
        )
        Log.d(TAG, "[season ] AJAX completion returned ${html.length} chars")
        Jsoup.parse(html, firstEpisode)
    }.onFailure {
        Log.e(TAG, "[season ] AJAX completion failed: ${it.message}")
    }.getOrNull()
}

/** Fetch one server slot's embed URL via the theme AJAX endpoint; retries once. */
private suspend fun fetchServerEmbed(
    ajaxServer: String,
    postId: String,
    server: String,
    watchUrl: String,
): String? {
    repeat(2) { attempt ->
        try {
            val html = cfPostText(
                ajaxServer,
                data = mapOf("id" to postId, "i" to server),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = watchUrl,
                timeout = 15000,
            )
            val src = Jsoup.parse(html).selectFirst("iframe")?.attr("src").orEmpty()
            if (src.startsWith("http")) {
                Log.d(TAG, "[slot $server t$attempt] iframe=$src")
                return src
            }
            Log.d(TAG, "[slot $server t$attempt] EMPTY (${html.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "[slot $server t$attempt] error: ${e.message}")
        }
    }
    return null
}

private suspend fun extractServers(
    watchUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = coroutineScope {
    try {
        val watchText = cfGetText(watchUrl, timeout = 15000)
        val watchDoc = Jsoup.parse(watchText, watchUrl)

        // The AJAX base is defined per-page (theme dir can change between
        // mirrors); fall back to the known constant.
        val ajaxServer = Regex("""MyAjaxURL\s*=\s*["']([^"']+)["']""").find(watchText)
            ?.groupValues?.get(1)?.trimEnd('/')?.plus("/Single/Server.php")
            ?: TOPCINEMA_AJAX_SERVER

        // The watch page always renders its default player inline — use it even
        // if the AJAX endpoint misbehaves.
        val inlineIframe = watchDoc.selectFirst(".player--iframe iframe")?.attr("src")
            ?.takeIf { it.startsWith("http") }

        val servers = watchDoc.select(".watch--servers--list li.server--item")
        val postId = servers.first()?.attr("data-id").orEmpty()
        Log.d(TAG, "[watch  ] servers=${servers.size} postId=$postId inline=${inlineIframe != null}")

        // Deduplicate inline iframe vs AJAX embeds (re-3arabi TopCinema ConcurrentHashMap style).
        val seenEmbeds = LinkedHashSet<String>()
        inlineIframe?.let { seenEmbeds.add(it) }

        var found = false
        if (inlineIframe != null) {
            found = true
            EmbedRouter.route(inlineIframe, watchUrl, subtitleCallback, callback, "TopCinema")
        }

        if (postId.isNotBlank() && servers.isNotEmpty()) {
            val embeds = servers.mapNotNull { li ->
                val server = li.attr("data-server").ifBlank { li.attr("data-i") }
                if (server.isBlank()) null
                else async { fetchServerEmbed(ajaxServer, postId, server, watchUrl) }
            }.awaitAll().filterNotNull()
                .filter { seenEmbeds.add(it) } // skip dup of inline iframe
            Log.d(TAG, "[watch  ] ajax embeds=${embeds.size}/${servers.size}")

            if (embeds.isNotEmpty()) {
                found = true
                embeds.amap { embed ->
                    async { EmbedRouter.route(embed, watchUrl, subtitleCallback, callback, "TopCinema") }
                }.awaitAll()
            }
        }

        found
    } catch (e: Exception) {
        Log.e(TAG, "[watch  ] extractServers failed: ${e.message}")
        false
    }
}

/**
 * TopCinema exposes a parallel `/download/` page listing raw/embed links via
 * `a.downloadsLink`. Some are wrapped in `play.php?to=<encoded>` (unwrap), the
 * rest are routed through the shared extractor registry.
 */
private suspend fun topcinemaDownloadLinks(
    downloadUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = coroutineScope {
    return@coroutineScope try {
        val html = cfGetText(downloadUrl, timeout = 15000)
        val doc = Jsoup.parse(html, downloadUrl)
        val links = doc.select("a.downloadsLink")
            .mapNotNull { it.absUrl("href").ifEmpty { it.attr("href") } }
            .filter { it.startsWith("http") }
        Log.d(TAG, "[download] ${links.size} links on $downloadUrl")
        links.forEach { raw ->
            val url = unwrapPlayUrl(raw)
            EmbedRouter.route(url, downloadUrl, subtitleCallback, callback, "TopCinema")
        }
        links.isNotEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "[download] failed: ${e.message}")
        false
    }
}

/** Resolve a TopCinema post's watch servers and its parallel download page. */
private suspend fun topcinemaWithDownload(
    epUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val base = epUrl.trimEnd('/')
    val ok = extractServers("$base/watch/", subtitleCallback, callback)
    val dl = topcinemaDownloadLinks("$base/download/", subtitleCallback, callback)
    return ok || dl
}

// ---------------------------------------------------------------------------
// Mirror handling — these sites rotate domains constantly; the seed domain
// usually 301s to the current mirror. Resolve the final origin once and reuse.
// ---------------------------------------------------------------------------

private val originCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private suspend fun resolveOrigin(seedUrl: String): String {
    originCache[seedUrl]?.let { return it }
    val resolved = runCatching {
        val response = app.get(seedUrl, timeout = 15000)
        val uri = java.net.URI(response.url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(seedUrl)
    originCache[seedUrl] = resolved
    Log.d("StreamlyMirror", "resolveOrigin($seedUrl) -> $resolved")
    return resolved
}

private fun getBaseUrl(url: String): String =
    runCatching { val u = java.net.URI(url); "${u.scheme}://${u.host}" }.getOrDefault(url)

internal fun fixUrl(url: String, base: String): String {
    if (url.startsWith("http")) return url
    if (url.startsWith("//")) return "https:$url"
    val host = runCatching { java.net.URI(base).let { "${it.scheme}://${it.host}" } }.getOrDefault(base)
    return if (url.startsWith("/")) "$host$url" else "$host/$url"
}

/** Some embed hosts wrap the real URL in `play.php?to=<encoded>`; unwrap it. */
private fun unwrapPlayUrl(url: String): String {
    if (!url.contains("play.php?to=")) return url
    return runCatching {
        val decoded = java.net.URLDecoder.decode(url.substringAfter("play.php?to="), "UTF-8").trim()
        if (decoded.startsWith("http")) decoded else "https:${decoded.trimStart(':')}"
    }.getOrDefault(url)
}

// ---------------------------------------------------------------------------
// WeCima (wecima.cx) link source.
//
// Custom theme (not WordPress): search is a JSON API (POST /search returning
// {slug, istv, year}), watch servers are base64-encoded inside
// `ul.WatchServersList li btn[data-url]`, downloads in `.openLinkDown[data-href]`.
// Multi-season shows list seasons on the series page
// (`a.SeasonsEpisodes[data-id][data-season]`) and resolve episodes via
// POST /ajax/Episode; single-season shows list episodes inline.
// ---------------------------------------------------------------------------

private const val WECIMA_MAIN_URL = "https://wecima.ac"
private const val WECIMA_TAG = "WeCima"

data class WecimaSearchItem(
    val title: String? = null,
    val slug: String? = null,
    val year: String? = null,
    val istv: Int? = null,
    val rating: String? = null,
)

data class WecimaSearchResponse(
    val status: Boolean? = null,
    val results: List<WecimaSearchItem>? = null,
)

suspend fun invokeWecima(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    Log.d(WECIMA_TAG, "[invoke] title=$title year=${res.year} movie=${res.isMovie} s=${res.season} e=${res.episode}")
    StreamlyDiag.lastStage = "WeCima: start"
    if (title.isEmpty()) return false

    var emitted = 0
    val countingCallback: (ExtractorLink) -> Unit = { emitted++; callback(it) }

    return try {
        val ok = if (res.isMovie) {
            wecimaResolveMovie(title, res.year, subtitleCallback, countingCallback)
        } else {
            wecimaResolveEpisode(title, res.season ?: 1, res.episode ?: 1, res.year, subtitleCallback, countingCallback)
        }
        Log.d(WECIMA_TAG, "[done  ] emitted=$emitted")
        StreamlyDiag.lastStage = if (emitted > 0) "WeCima: ok" else "WeCima: no links"
        ok || emitted > 0
    } catch (e: Exception) {
        Log.e(WECIMA_TAG, "[invoke] failed: ${e.message}")
        StreamlyDiag.lastStage = "WeCima: ${e.message}"
        emitted > 0
    }
}

/** Search API returns JSON; slugs are hyphen-separated Latin/Arabic mixes. */
private suspend fun wecimaSearch(query: String): List<WecimaSearchItem> =
    withContext(Dispatchers.IO) {
        try {
            val base = resolveOrigin(WECIMA_MAIN_URL)
            val text = cfPostText(
                "$base/search",
                data = mapOf("q" to query),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = base,
                timeout = 15000,
            )
            parseJson<WecimaSearchResponse>(text).results.orEmpty()
        } catch (e: Exception) {
            Log.e(WECIMA_TAG, "[search ] failed: ${e.message}")
            emptyList()
        }
    }

/**
 * WeCima slugs are token-reversed with Arabic noise words interleaved
 * ("2023-مترجم-oppenheimer-مشاهدة-فيلم"), so the prefix/suffix stripping in
 * latinTitleFromSlug doesn't apply. Keep only tokens without Arabic chars.
 */
private fun wecimaLatinFromSlug(slug: String): String =
    slug.split('-')
        .filter { token ->
            token.isNotBlank() && token.none { ch -> ch.code in 0x0600..0x06FF }
        }
        .joinToString(" ")
        .trim()

/**
 * Scores a search item against the TMDB title/year using the Latin part of
 * the slug (see wecimaLatinFromSlug).
 */
private fun scoreWecimaItem(item: WecimaSearchItem, title: String, year: Int?): Int {
    val slug = item.slug.orEmpty()
    var score = FuzzySearch.weightedRatio(
        wecimaLatinFromSlug(slug).lowercase().replace('-', ' '),
        title.lowercase().replace('-', ' '),
    )
    val itemYear = item.year?.toIntOrNull() ?: yearFromSlug(slug)
    if (itemYear != null && year != null && Math.abs(itemYear - year) > 1) {
        score -= YEAR_PENALTY
    } else if (itemYear != null && year != null) {
        score += 5
    }
    return score
}

/** Servers are base64 ("aHR0c..." = "http...") with '+' stripped. */
private fun wecimaDecode(encoded: String): String? {
    if (encoded.isBlank()) return null
    val cleaned = encoded.replace("+", "").trim()
    val b64 = if (cleaned.startsWith("aHR0c")) cleaned else "aHR0c$cleaned"
    val padded = b64.padEnd((b64.length + 3) / 4 * 4, '=')
    return runCatching {
        String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
    }.getOrNull()?.takeIf { it.startsWith("http") }
}

private fun normalizeWeCimaEmbed(url: String): String {
    var u = url.trim()
    runCatching {
        val uri = URI(u)
        val host = uri.host?.lowercase()
        if (host != null) {
            val scheme = (uri.scheme ?: "https").lowercase()
            val port = if (uri.port != -1) ":${uri.port}" else ""
            var path = (uri.rawPath ?: "").trimEnd('/')
            // WeCima lists same video as /e/<id> and /d/<id> or /f/<id> - collapse to same ID
            path = path.replace(Regex("/[edf]/"), "/x/")
            u = "$scheme://$host$port$path"
        }
    }
    return u.trimEnd('/').lowercase().let { if (it.isBlank()) url.trim() else it }
}

private suspend fun wecimaExtractPost(
    postUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = coroutineScope {
    try {
        val doc = cfGetDoc(postUrl, timeout = 15000)
        // Dedup by normalized host+path to collapse http/https, trailing slash, query variants.
        val seenNorm = HashSet<String>()
        val embeds = LinkedHashSet<String>()
        fun addEmbed(raw: String?) {
            if (raw.isNullOrBlank()) return
            val fixed = fixUrl(raw.trim(), postUrl)
            if (!fixed.startsWith("http")) return
            val norm = normalizeWeCimaEmbed(fixed)
            if (seenNorm.add(norm)) embeds.add(fixed) else Log.d(WECIMA_TAG, "[dedup ] wecima embed dup $fixed -> $norm")
        }
        doc.select("ul.WatchServersList li btn[data-url]").forEach { btn ->
            wecimaDecode(btn.attr("data-url"))?.let { addEmbed(it) }
        }
        // Download-quality servers are base64-encoded in .openLinkDown[data-href].
        doc.select(".openLinkDown[data-href]").forEach { el ->
            wecimaDecode(el.attr("data-href"))?.let { addEmbed(it) }
        }
        // Older layouts expose plain iframes instead of encoded buttons.
        doc.select("iframe[src]").forEach { fr ->
            addEmbed(fr.attr("src"))
        }
        Log.d(WECIMA_TAG, "[servers] ${embeds.size} distinct (norm ${seenNorm.size}) on $postUrl")
        embeds.toList().amap { embed ->
            async { EmbedRouter.route(embed, postUrl, subtitleCallback, callback, "WeCima") }
        }.awaitAll()
        true
    } catch (e: Exception) {
        Log.e(WECIMA_TAG, "[servers] failed for $postUrl: ${e.message}")
        false
    }
}

private suspend fun wecimaResolveMovie(
    title: String,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val base = resolveOrigin(WECIMA_MAIN_URL)
    val best = wecimaSearch(title)
        .filter { it.istv == 0 && !it.slug.isNullOrBlank() }
        .map { it to scoreWecimaItem(it, title, year) }
        .filter { it.second >= MIN_SCORE_MOVIE }
        .maxByOrNull { it.second }?.first
    if (best == null) {
        Log.d(WECIMA_TAG, "[match  ] no movie above $MIN_SCORE_MOVIE")
        return false
    }
    val url = "$base/watch/${java.net.URLEncoder.encode(best.slug, "UTF-8")}"
    Log.d(WECIMA_TAG, "[match  ] WINNER $url")
    return wecimaExtractPost(url, subtitleCallback, callback)
}

private suspend fun wecimaResolveEpisode(
    title: String,
    season: Int,
    episode: Int,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val base = resolveOrigin(WECIMA_MAIN_URL)
    val best = wecimaSearch(title)
        .filter { it.istv == 1 && !it.slug.isNullOrBlank() }
        .map { it to scoreWecimaItem(it, title, year) }
        .filter { it.second >= MIN_SCORE_SERIES }
        .maxByOrNull { it.second }?.first
    if (best == null) {
        Log.d(WECIMA_TAG, "[match  ] no series above $MIN_SCORE_SERIES")
        return false
    }
    val seriesUrl = "$base/series/${java.net.URLEncoder.encode(best.slug, "UTF-8")}"
    Log.d(WECIMA_TAG, "[match  ] anchor $seriesUrl")

    val doc = cfGetDoc(seriesUrl, timeout = 15000)

    // Multi-season: season tabs carry the AJAX params for /ajax/Episode.
    val seasonTabs = doc.select("a.SeasonsEpisodes[data-id][data-season]")
    if (seasonTabs.isEmpty()) {
        // Single-season show: episodes listed inline on the page itself.
        val epUrl = wecimaExactEpisode(doc, episode) ?: run {
            Log.d(WECIMA_TAG, "[match  ] E$episode not in inline list")
            return false
        }
        return wecimaExtractPost(epUrl, subtitleCallback, callback)
    }

    val tab = seasonTabs.firstOrNull { el ->
        val fromAttr = Regex("""\d+""").find(el.attr("data-season"))?.value?.toIntOrNull()
        val fromText = Regex("""الموسم\s*(\d+)""").find(el.text())?.groupValues?.get(1)?.toIntOrNull()
        fromAttr == season || fromText == season
    } ?: run {
        Log.d(WECIMA_TAG, "[season ] S$season not among ${seasonTabs.size} tabs")
        return false
    }

    val listHtml = runCatching {
        cfPostText(
            "$base/ajax/Episode",
            data = mapOf(
                "post_id" to tab.attr("data-id"),
                "season" to tab.attr("data-season"),
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = seriesUrl,
            timeout = 15000,
        )
    }.getOrElse {
        Log.e(WECIMA_TAG, "[season ] ajax failed: ${it.message}")
        return false
    }

    val listDoc = Jsoup.parse(listHtml, seriesUrl)
    val epUrl = wecimaExactEpisode(listDoc, episode) ?: run {
        Log.d(WECIMA_TAG, "[match  ] E$episode not in S$season ajax list")
        return false
    }
    Log.d(WECIMA_TAG, "[match  ] WINNER S${season}E${episode} $epUrl")
    return wecimaExtractPost(epUrl, subtitleCallback, callback)
}

/** Exact episode match by episodetitle label or slug number. */
private fun wecimaExactEpisode(scope: Document, episode: Int): String? {
    val anchors = scope.select(".EpisodesList a[href], a.hoverable.activable[href]")
        .takeIf { it.isNotEmpty() } ?: scope.select("a[href]")
    for (a in anchors) {
        val href = a.absUrl("href").ifEmpty { a.attr("href") }
        if (!href.contains("/watch/")) continue
        val num = a.selectFirst("episodetitle")?.text()
            ?.let { EPISODE_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: EPISODE_REGEX.find(decodeSlug(href))?.groupValues?.get(1)?.toIntOrNull()
        if (num == episode) return href
    }
    return null
}

// ---------------------------------------------------------------------------
// EgyDead (egydead.skin, currently serving from tvN.egydead.live) link source.
//
// Search returns movie posts ({title}-{year}-{quality}) and per-episode posts
// (/episode/{slug}-sXXeXX). Watch servers are NOT in the static HTML: they
// appear after re-POSTing the post URL with `View=1`, as
// `ul.serversList li[data-link]` embeds.
// ---------------------------------------------------------------------------

private const val EGYDEAD_ENTRY_URL = "https://egydead.beer"
private const val EGYDEAD_TAG = "EgyDead"
private val EGYDEAD_EPISODE_URL_REGEX = Regex("""-s(\d{1,2})e(\d{1,3})""", RegexOption.IGNORE_CASE)

/** EgyDead redirects to a rotating mirror; resolve the live origin and reuse it. */
private suspend fun egydeadBase(): String = resolveOrigin(EGYDEAD_ENTRY_URL)

/** Arabic ordinal season words, alef-normalized matching. */
private val ARABIC_ORDINALS = linkedMapOf(
    "الاول" to 1, "اول" to 1,
    "الثاني" to 2, "ثاني" to 2,
    "الثالث" to 3, "ثالث" to 3,
    "الرابع" to 4, "رابع" to 4,
    "الخامس" to 5, "خامس" to 5,
    "السادس" to 6, "سادس" to 6,
    "السابع" to 7, "سابع" to 7,
    "الثامن" to 8, "ثامن" to 8,
    "التاسع" to 9, "تاسع" to 9,
    "العاشر" to 10, "عاشر" to 10,
)

/**
 * Extracts the season number from an EgyDead slug. Handles digit seasons
 * (الموسم-4) and Arabic ordinals (الموسم-الرابع); returns -1 when absent.
 */
private fun egydeadSeasonFromSlug(slug: String): Int {
    SEASON_DIGIT_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val normalized = slug.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    for ((word, num) in ARABIC_ORDINALS) {
        if (normalized.contains("الموسم-$word") || normalized.contains("موسم-$word") ||
            normalized.contains("الموسم-$word-") || normalized.contains("موسم-$word-")
        ) return num
    }
    return -1
}

/** URLs that are ads/trackers, never players. */
private val BLOCKED_EMBED_KEYWORDS = listOf(
    "recaptcha", "googlesyndication", "googletagmanager", "google-analytics",
)

suspend fun invokeEgydead(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    Log.d(EGYDEAD_TAG, "[invoke] title=$title year=${res.year} movie=${res.isMovie} s=${res.season} e=${res.episode}")
    StreamlyDiag.lastStage = "EgyDead: start"
    if (title.isEmpty()) return false

    var emitted = 0
    val countingCallback: (ExtractorLink) -> Unit = { emitted++; callback(it) }

    return try {
        val ok = if (res.isMovie) {
            egydeadResolveMovie(title, res.year, subtitleCallback, countingCallback)
        } else {
            egydeadResolveEpisode(title, res.season ?: 1, res.episode ?: 1, res.year, subtitleCallback, countingCallback)
        }
        Log.d(EGYDEAD_TAG, "[done  ] emitted=$emitted")
        StreamlyDiag.lastStage = if (emitted > 0) "EgyDead: ok" else "EgyDead: no links"
        ok || emitted > 0
    } catch (e: Exception) {
        Log.e(EGYDEAD_TAG, "[invoke] failed: ${e.message}")
        StreamlyDiag.lastStage = "EgyDead: ${e.message}"
        emitted > 0
    }
}

/**
 * Searches up to [maxPages] result pages. The entry domain redirects to the
 * current mirror; subsequent pages reuse the discovered origin.
 */
private suspend fun egydeadSearch(query: String, maxPages: Int = 3): List<Candidate> {
    val out = ArrayList<Candidate>()
    var base = egydeadBase()
    val encoded = URLEncoder.encode(query, "UTF-8")
    for (page in 1..maxPages) {
        val url = if (page == 1) "$base/?s=$encoded" else "$base/page/$page/?s=$encoded"
        try {
            val html = cfGetText(url, timeout = 15000)
            val doc = Jsoup.parse(html, url)
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            val anchors = doc.select("a[href]")
            var added = 0
            for (a in anchors) {
                val href = a.absUrl("href").ifEmpty { a.attr("href") }
                val hrefHost = runCatching { java.net.URI(href).host }.getOrNull()
                if (hrefHost == null || !hrefHost.equals(host, ignoreCase = true)) continue
                if (Regex("""/(category|tag|page|quality|type|language|series-category|episode)/?$""").containsMatchIn(href)) continue
                val slug = decodeSlug(href)
                if (slug.isBlank()) continue
                val candidate = Candidate(href, slug, latinTitleFromSlug(slug), yearFromSlug(slug))
                if (candidate.latinTitle.isBlank()) continue
                if (out.any { it.url == href }) continue
                out.add(candidate)
                added++
            }
            Log.d(EGYDEAD_TAG, "[search ] page $page -> +$added (${out.size} total)")
            if (added == 0) break
        } catch (e: Exception) {
            Log.e(EGYDEAD_TAG, "[search ] page $page failed: ${e.message}")
            StreamlyDiag.lastStage = "EgyDead: search failed: ${e.message}"
            break
        }
    }
    if (out.isEmpty()) StreamlyDiag.lastStage = "EgyDead: search empty"
    return out
}

private suspend fun egydeadExtract(
    postUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = coroutineScope {
    // Mirrors re-3arabi EgyDead: priming GET, then POST to ?view=watch with View=1 + X-Requested-With,
    // fallback to GET if POST fails. See /tmp/re-3arabi/Egydead/src/main/kotlin/com/egydead/egydeadProvider.kt:718
    val originalUrl = postUrl
    val watchPageUrl = if (!postUrl.contains("?view=watch")) "$postUrl?view=watch" else postUrl
    try { cfGetDoc(originalUrl, timeout = 15000) } catch (_: Exception) {}
    val html = try {
        cfPostText(
            watchPageUrl,
            data = mapOf("View" to "1"),
            referer = originalUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            timeout = 20000
        )
    } catch (e: Exception) {
        Log.e(EGYDEAD_TAG, "[watch  ] View=1 POST failed: ${e.message}, trying fallback GET $watchPageUrl")
        try {
            cfGetText(watchPageUrl, referer = originalUrl, timeout = 20000)
        } catch (e2: Exception) {
            Log.e(EGYDEAD_TAG, "[watch  ] fallback GET failed: ${e2.message}")
            null
        }
    } ?: return@coroutineScope false

    val doc = Jsoup.parse(html, watchPageUrl)
    // url -> server label (so we can special-case EarnVids/StreamHG)
    val watchEmbeds = LinkedHashSet<Pair<String, String>>()
    // download URL -> quality label from the site's <em>1080p</em> markers
    val downloadCandidates = LinkedHashMap<String, String>()

    fun serverLabel(el: Element): String =
        el.selectFirst(".ser-name, p, .server-info")?.text()?.trim().orEmpty()

    // Watch server lists (data-link carries the embed URL). Mirrors re-3arabi watchSelectors.
    for (sel in listOf(
        "ul.serversList li",
        "ul.servers-list li",
        "div.serversList li",
        "div.servers-list li",
    )) {
        doc.select(sel).forEach { li ->
            val dataLink = li.attr("data-link").takeIf { it.isNotBlank() }
            val childDataLink = li.selectFirst("[data-link]")?.attr("data-link")
            val hrefFromBtn = li.selectFirst("button[data-link]")?.attr("data-link")
            val hrefFromA = li.selectFirst("a[href]")?.attr("href")
            val link = dataLink ?: childDataLink ?: hrefFromBtn ?: hrefFromA ?: ""
            if (link.startsWith("http")) watchEmbeds.add(link to serverLabel(li))
        }
    }

    // Generic: any <a> pointing at a player/embed/drive host.
    val playerHostRegex = Regex("""(player|embed|drive|load|watch|wish|vid)\b""", RegexOption.IGNORE_CASE)
    doc.select("a[href]").forEach { a ->
        val href = a.absUrl("href").ifEmpty { a.attr("href") }
        if (href.startsWith("http") && playerHostRegex.containsMatchIn(href)) {
            watchEmbeds.add(href to serverLabel(a))
        }
    }

    // Download lists (multi-quality direct/file-host links).
    for (sel in listOf(
        "ul.donwload-servers-list li",
        "ul.download-servers-list li",
        "ul.donwload-servers-list > li",
        "div.donwload-servers-list li",
        "div.download-servers-list li",
    )) {
        doc.select(sel).forEach { li ->
            val link = li.selectFirst("a.ser-link")?.attr("href")
                ?: li.selectFirst("a[href]")?.attr("href")
                ?: li.attr("data-link")
            if (!link.startsWith("http")) continue
            val quality = li.selectFirst(".server-info em")?.text()?.trim()
                ?: li.selectFirst("em")?.text()?.trim()
            downloadCandidates.putIfAbsent(link, quality.orEmpty())
        }
    }

    // Generic fallback: any remaining data-link carriers.
    doc.select("[data-link]").forEach { el ->
        val link = el.attr("data-link")
        if (link.startsWith("http")) watchEmbeds.add(link to serverLabel(el))
    }

    val filteredWatch = watchEmbeds.filter { (embed, _) ->
        BLOCKED_EMBED_KEYWORDS.none { embed.contains(it, ignoreCase = true) }
    }

    // Cross-section dedup: download list often repeats watch embeds.
    val watchUrls = filteredWatch.map { it.first }.toSet()
    val filteredDownloads = downloadCandidates.filterKeys { it !in watchUrls }

    Log.d(EGYDEAD_TAG, "[watch  ] ${filteredWatch.size} embeds + ${filteredDownloads.size} download links on $postUrl (deduped ${downloadCandidates.size - filteredDownloads.size})")
    if (filteredWatch.isEmpty() && filteredDownloads.isEmpty()) {
        StreamlyDiag.lastStage = "EgyDead: no servers on post"
    }

    val jobs = ArrayList<suspend () -> Unit>(filteredWatch.size + filteredDownloads.size)
    filteredWatch.forEach { (embed, label) ->
        if (label.contains("earnvids", true) || label.contains("streamhg", true)) {
            // Custom HLS resolver for EarnVids/StreamHG (packed player, enc m3u8).
            jobs += suspend {
                val m3u8 = ExternalEarnVidsExtractor.extract(embed, postUrl)
                if (!m3u8.isNullOrBlank()) {
                    generateM3u8("EgyDead - $label", m3u8, referer = embed).forEach(callback)
                } else {
                    EmbedRouter.route(embed, postUrl, subtitleCallback, callback, "EgyDead")
                }
            }
        } else {
            jobs += suspend { EmbedRouter.route(embed, postUrl, subtitleCallback, callback, "EgyDead") }
        }
    }
    // Download links: raw video files (.mp4/.mkv) are emitted directly with
    // their quality label; everything else (file-lockers) only counts if an
    // extractor can resolve it (shared plugin registry: Dood/EarnVids/etc.).
    val directFileRegex = Regex("""\.(mp4|mkv)([?#].*)?$""", RegexOption.IGNORE_CASE)
    filteredDownloads.forEach { (link, quality) ->
        if (directFileRegex.containsMatchIn(link)) {
            jobs += suspend {
                val label = buildString {
                    append("EgyDead")
                    if (quality.isNotBlank()) append(" - $quality")
                }
                callback(newExtractorLink(label, label, url = link) {
                    this.referer = postUrl
                    this.quality = getQualityFromName(link)
                    this.type = ExtractorLinkType.VIDEO
                })
            }
        } else {
            jobs += suspend {
                runCatching {
                    loadExtractor(link, postUrl, subtitleCallback, { l ->
                        callback(relabelLink(l, "EgyDead"))
                    })
                }
            }
        }
    }

    jobs.amap { job -> async { job() } }.awaitAll()
    filteredWatch.isNotEmpty() || downloadCandidates.isNotEmpty()
}

private suspend fun egydeadResolveMovie(
    title: String,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val best = egydeadSearch(title).map { it to scoreCandidate(it, title, year) }
        .filter { (c, s) -> !c.url.contains("/episode/") && s >= MIN_SCORE_MOVIE }
        .maxByOrNull { it.second }?.first
    if (best == null) {
        Log.d(EGYDEAD_TAG, "[match  ] no movie above $MIN_SCORE_MOVIE")
        return false
    }
    Log.d(EGYDEAD_TAG, "[match  ] WINNER ${best.url}")
    return egydeadExtract(best.url, subtitleCallback, callback)
}

private suspend fun egydeadResolveEpisode(
    title: String,
    season: Int,
    episode: Int,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    // Preferred path: search results include per-season pages
    // (/season/…الموسم-N…) that list every episode with الحلقة-N numbering.
    val results = egydeadSearch(title)
    val seasonPage = results
        .filter { it.url.contains("/season/") }
        .map { it to egydeadSeasonFromSlug(it.slug) }
        .firstOrNull { (_, s) -> s == season }?.first

    if (seasonPage != null) {
        Log.d(EGYDEAD_TAG, "[season ] page ${seasonPage.url}")
        val doc = cfGetDoc(seasonPage.url, timeout = 15000)
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            if (!href.contains("/episode/")) continue
            val slug = decodeSlug(href)
            val ep = EPISODE_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (ep != episode) continue
            val slugSeason = egydeadSeasonFromSlug(slug)
            if (slugSeason != -1 && slugSeason != season) continue
            Log.d(EGYDEAD_TAG, "[match  ] WINNER S${season}E${episode} $href")
            return egydeadExtract(href, subtitleCallback, callback)
        }
        Log.d(EGYDEAD_TAG, "[match  ] E$episode not on S$season page")
        return false
    }

    // Fallback: shows using Latin -sXXeXX episode slugs.
    val match = results
        .filter { EGYDEAD_EPISODE_URL_REGEX.containsMatchIn(decodeSlug(it.url)) }
        .mapNotNull { c ->
            val m = EGYDEAD_EPISODE_URL_REGEX.find(decodeSlug(c.url)) ?: return@mapNotNull null
            val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val score = scoreCandidate(c, title, year)
            Triple(c, s to e, score)
        }
        .filter { (_, se, score) -> se.first == season && se.second == episode && score >= MIN_SCORE_SERIES }
        .maxByOrNull { it.third }?.first
    if (match == null) {
        Log.d(EGYDEAD_TAG, "[match  ] S${season}E$episode not found in search results")
        return false
    }
    Log.d(EGYDEAD_TAG, "[match  ] WINNER ${match.url}")
    return egydeadExtract(match.url, subtitleCallback, callback)
}

// ===========================================================================
// FaselHD (fasel-hd.cam) link source.
//
// WordPress (faselhd_2020 theme). Search is performed via `?s=` and is
// Cloudflare-protected; `faselHdGet` routes through the shared WebView-based
// CloudFlare solver (see cfGetDoc) when a "Just a moment…" challenge page is
// returned. Post pages expose a player
// iframe (iframe[name="player_iframe"] / iframe[src*="video_player"]) plus a
// direct download path; resolved servers are routed through EmbedRouter so we
// reuse the shared extractor registry (Vidtube/UpDown/Dooood/Filelion/…).
// ===========================================================================

internal const val FASELHD_MAIN_URL = "https://web31312x.faselhdx.bid"
internal const val FASELHD_FALLBACK_URL = "https://www.fasel-hd.cam"
internal const val FASELHD_TAG = "FaselHD"

/** Live-mirror winner, cached per process (mirrors die across days, not minutes). */
private var faselHdLiveBase: String? = null

/** Theme markers proving a fetched page is really FaselHD, not a parked/dead mirror. */
private fun isFaselHdPage(html: String?): Boolean {
    if (html.isNullOrBlank()) return false
    return html.contains("postDiv", ignoreCase = true) ||
        html.contains("dtc_live", ignoreCase = true) ||
        html.contains("faselhd", ignoreCase = true) ||
        html.contains("فاصل", ignoreCase = true)
}

internal suspend fun faselHdBase(): String {
    faselHdLiveBase?.let { return it }
    // Probe each known mirror and use the first that serves real FaselHD
    // content — numbered .bid mirrors die often and resolveOrigin alone only
    // follows redirects, so a parked 200 page would otherwise stick.
    for (seed in listOf(FASELHD_MAIN_URL, FASELHD_FALLBACK_URL)) {
        val origin = resolveOrigin(seed)
        // Explicit String? type: app response accessors carry a jspecify
        // @Nullable annotation that isn't on the compile classpath.
        val probe: String? = try {
            app.get(origin, timeout = 15000).text
        } catch (_: Exception) {
            null
        }
        if (isFaselHdPage(probe)) {
            Log.d("StreamlyMirror", "faselHdBase probing $seed -> live $origin")
            faselHdLiveBase = origin
            return origin
        }
        Log.w(FASELHD_TAG, "[mirror ] $seed -> $origin not serving FaselHD, trying next")
    }
    val fallback = resolveOrigin(FASELHD_MAIN_URL)
    StreamlyDiag.lastStage = "FaselHD: no live mirror"
    return fallback
}

/**
 * Resolved FaselHD origin for the manual Cloudflare solve dialog in settings.
 * Same host the provider requests hit (via [faselHdBase]), so the
 * cf_clearance cookie the user earns applies to subsequent requests.
 */
internal suspend fun faselHdSolveUrl(): String = faselHdBase()

/** CF-aware GET backed by the shared WebView solver (see cfGetDoc). */
private suspend fun faselHdGet(url: String, referer: String? = null): Document =
    cfGetDoc(url, referer = referer, timeout = 20000)

/** Shared card parser for FaselHD list fragments (`?s=` pages and AJAX html). */
private fun faselHdParseCards(doc: Document): List<Candidate> =
    doc.select("div#postList div.postDiv, div.postDiv, article, .result, .search-item").mapNotNull { box ->
        val a = box.selectFirst("a[href]") ?: return@mapNotNull null
        val href = a.absUrl("href").ifEmpty { a.attr("href") }
        if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
        val slug = decodeSlug(href)
        Candidate(href, slug, latinTitleFromSlug(slug), yearFromSlug(slug))
    }

/**
 * FaselHD live-search via the theme's AJAX endpoint (`action=dtc_live`),
 * ported from re-3arabi's Faselhd.kt: raw OkHttp POST with manual redirect
 * handling and one CF-clearance refresh on HTTP 403. Unlike `?s=`, the
 * server matches Arabic titles too, so this is the primary search path.
 */
private suspend fun faselHdAjaxSearch(query: String, base: String): List<Candidate> {
    val formBody = FormBody.Builder()
        .add("action", "dtc_live")
        .add("trsearch", query)
        .build()
    val bodyStr = try {
        faselHdAjaxPost("$base/wp-admin/admin-ajax.php", base, formBody)
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[search ] ajax failed: ${e.message}")
        null
    }
    if (bodyStr.isNullOrBlank()) return emptyList()
    return faselHdParseCards(Jsoup.parse(bodyStr, base))
}

/**
 * Raw OkHttp AJAX POST with manual redirect handling (re-3arabi
 * `makeAjaxRequest`). Throws IllegalStateException("403_FORBIDDEN") on a
 * Cloudflare block so the caller can refresh clearance and retry once
 * (re-3arabi `executeRequestWithCloudflareRetry`).
 */
private suspend fun faselHdAjaxPost(
    ajaxUrl: String,
    base: String,
    formBody: FormBody,
): String? {
    try {
        return faselHdAjaxAttempt(ajaxUrl, base, formBody, cfCookies(base))
    } catch (e: IllegalStateException) {
        if (e.message != "403_FORBIDDEN") return null
    }
    Log.d(FASELHD_TAG, "[search ] 403 on AJAX, refreshing CF clearance…")
    cfSolve(ajaxUrl)
    return try {
        faselHdAjaxAttempt(ajaxUrl, base, formBody, cfCookies(base))
    } catch (_: Exception) {
        null
    }
}

private suspend fun faselHdAjaxAttempt(
    ajaxUrl: String,
    base: String,
    formBody: FormBody,
    cookies: String,
): String? {
    val client = app.baseClient.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followRedirects(false) // follow manually to keep the POST across hops
        .build()
    var currentUrl = ajaxUrl
    var redirects = 0
    var rateRetries = 0
    while (redirects < 5) {
        val reqBuilder = OkRequest.Builder()
            .url(currentUrl)
            .post(formBody)
            .header("User-Agent", CF_UA)
            .header("Referer", base)
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)
        val response = withContext(Dispatchers.IO) {
            client.newCall(reqBuilder.build()).execute()
        }
        var backoffMs = 0L
        response.use { res ->
            when (res.code) {
                403 -> throw IllegalStateException("403_FORBIDDEN")
                429 -> {
                    // Rate-limited infra: back off and retry (re-3arabi smartGet).
                    if (rateRetries >= 3) {
                        Log.w(FASELHD_TAG, "[search ] ajax 429 x3, giving up")
                        StreamlyDiag.lastStage = "FaselHD: ajax rate-limited"
                        return ""
                    }
                    rateRetries++
                    Log.w(FASELHD_TAG, "[search ] ajax 429, retry $rateRetries/3")
                    backoffMs = 1000L * rateRetries
                }
                301, 302, 307, 308 -> {
                    val location = res.header("Location")
                    if (location != null) {
                        currentUrl = if (location.startsWith("http")) location else "$base$location"
                        redirects++
                    } else {
                        return ""
                    }
                }
                200 -> return res.body?.string() ?: ""
                else -> {
                    Log.w(FASELHD_TAG, "[search ] ajax HTTP ${res.code}")
                    StreamlyDiag.lastStage = "FaselHD: ajax HTTP ${res.code}"
                    return ""
                }
            }
        }
        // 429 backoff happens here so the response is already closed.
        if (backoffMs > 0) delay(backoffMs)
    }
    return "" // redirect limit reached
}

private suspend fun faselHdSearch(query: String): List<Candidate> {
    val base = faselHdBase()

    // Primary: theme live-search AJAX (matches Arabic titles server-side).
    val primary = try {
        faselHdAjaxSearch(query, base)
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[search ] ajax failed: ${e.message}")
        emptyList()
    }
    if (primary.isNotEmpty()) {
        Log.d(FASELHD_TAG, "[search ] ajax '$query' -> ${primary.size} candidates")
        return primary
    }

    // Same AJAX with leading article stripped ("The X" -> "X"), which the
    // Arabic index often drops.
    val stripped = query.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim()
    if (stripped.isNotBlank() && stripped != query) {
        val retry = try {
            faselHdAjaxSearch(stripped, base)
        } catch (_: Exception) {
            emptyList()
        }
        if (retry.isNotEmpty()) {
            Log.d(FASELHD_TAG, "[search ] ajax stripped '$stripped' -> ${retry.size} candidates")
            return retry
        }
    }

    // Fallback: classic `?s=` GET. WordPress 302-redirects straight to the
    // post when there is a single hit — treat that as an exact hit instead
    // of parsing list cards that aren't there (re-3arabi resp.url pattern).
    Log.d(FASELHD_TAG, "[search ] ajax empty for '$query', trying ?s= fallback")
    try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$base/?s=$encoded"
        val finalUrl = try {
            app.get(searchUrl, timeout = 15000).url
        } catch (_: Exception) {
            searchUrl
        }
        if (!finalUrl.contains("?s=", ignoreCase = true) &&
            finalUrl.trimEnd('/') != base.trimEnd('/')
        ) {
            Log.d(FASELHD_TAG, "[search ] redirect hit -> $finalUrl")
            val slug = decodeSlug(finalUrl)
            // latinTitle = query scores 100 in scoreCandidate: exact hit.
            return listOf(Candidate(finalUrl, slug, query, yearFromSlug(slug)))
        }
        val items = faselHdParseCards(faselHdGet(searchUrl))
        Log.d(FASELHD_TAG, "[search ] ?s= '$query' -> ${items.size} candidates")
        if (items.isNotEmpty()) return items
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[search ] ?s= fallback failed: ${e.message}")
    }

    StreamlyDiag.lastStage = "FaselHD: search empty"
    return emptyList()
}

/** Find the episode anchor on a series (or season) page that matches `episode`. */
private fun faselHdExactEpisode(scope: Document, episode: Int): String? {
    val anchors = scope.select("div#epAll a").takeIf { it.isNotEmpty() } ?: scope.select("a[href]")
    for (a in anchors) {
        val href = a.absUrl("href").ifEmpty { a.attr("href") }
        if (!href.startsWith("http")) continue
        val label = a.ownText().ifBlank { a.text() }
        val ep = EPISODE_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: EPISODE_REGEX.find(decodeSlug(href))?.groupValues?.get(1)?.toIntOrNull()
            ?: continue
        if (ep != episode) continue
        return href
    }
    return null
}

private suspend fun faselHdExtractServers(
    postUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val base = faselHdBase()
    val doc = try {
        faselHdGet(postUrl)
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[watch  ] failed: ${e.message}")
        return false
    }
    var found = false

    // Direct download path: .downloadLinks a -> POST -> .dl-link a (final video).
    // The href is often relative — absolutize it or the POST below throws.
    val downloadRaw = doc.selectFirst(".downloadLinks a")?.let {
        it.absUrl("href").ifEmpty { it.attr("href") }
    }.orEmpty()
    val downloadHref = if (downloadRaw.isBlank()) "" else fixUrl(downloadRaw, base)
    if (downloadHref.isNotBlank()) {
        try {
            val playerDoc = cfPostDoc(downloadHref, referer = postUrl, timeout = 60000)
            val dlLink = playerDoc.select("div.dl-link a").attr("href")
            if (dlLink.isNotBlank()) {
                found = true
                callback(
                    newExtractorLink("FaselHD - Direct", "FaselHD - Direct", dlLink) {
                        this.referer = postUrl
                        this.quality = getQualityFromName(dlLink)
                        this.type = ExtractorLinkType.VIDEO
                    },
                )
            }
        } catch (e: Exception) {
            Log.e(FASELHD_TAG, "[direct ] failed: ${e.message}")
        }
    }

    // Player iframes -> WebView jwplayer decryption (enc: sources) + m3u8 sniff.
    val iframes = extractIframeSources(doc, base)
    Log.d(FASELHD_TAG, "[watch  ] ${iframes.size} iframes on $postUrl")
    if (iframes.isEmpty()) StreamlyDiag.lastStage = "FaselHD: 0 iframes"
    var resolved = false
    for (iframe in iframes) {
        val m3u8 = faselHdResolveWebView(iframe, postUrl)
        if (!m3u8.isNullOrBlank()) {
            found = true
            resolved = true
            generateM3u8(
                "FaselHD",
                m3u8,
                referer = iframe,
                headers = mapOf("Referer" to iframe, "User-Agent" to CF_UA),
            ).forEach(callback)
            break // first working server is enough
        }
    }

    // Fallback: the older inline-scan path for non-encrypted embeds.
    if (!resolved) {
        if (iframes.isNotEmpty()) StreamlyDiag.lastStage = "FaselHD: webview no m3u8"

        val iframeSrc = doc.select("iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]")
            .attr("src")
            .ifEmpty {
                val onclick = doc.selectFirst("ul.tabs-ul li[onclick], li.active[onclick]")?.attr("onclick")
                Regex("""'([^']+)'""").find(onclick ?: "")?.groupValues?.get(1)?.let {
                    if (it.startsWith("http")) it else "$base$it"
                } ?: ""
            }
        if (iframeSrc.isNotBlank()) {
            EmbedRouter.route(iframeSrc, postUrl, subtitleCallback, callback, "FaselHD")
            found = true
            try {
                val playerText = cfGetText(
                    iframeSrc,
                    referer = postUrl,
                    timeout = 20000,
                ).replace(Regex("""['"]\s*\+\s*['"]"""), "")
                val m3u8 = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").find(playerText)?.value
                if (!m3u8.isNullOrBlank()) {
                    found = true
                    generateM3u8("FaselHD", m3u8, referer = iframeSrc).forEach(callback)
                } else {
                    val mp4 = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""").find(playerText)?.value
                    if (!mp4.isNullOrBlank()) {
                        found = true
                        callback(
                            newExtractorLink("FaselHD MP4", "FaselHD MP4", mp4) {
                                this.referer = iframeSrc
                                this.quality = getQualityFromName(mp4)
                                this.type = ExtractorLinkType.VIDEO
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(FASELHD_TAG, "[scan   ] failed: ${e.message}")
            }
        }
    }

    return found
}

private suspend fun faselHdResolveMovie(
    title: String,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val candidates = faselHdSearch(title)
        .map { it to scoreCandidate(it, title, year) }
        .filter { it.second >= MIN_SCORE_MOVIE }

    val strippedTitle = title.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim()
    val best = candidates.maxByOrNull { it.second }?.first
        ?: if (strippedTitle.isNotBlank() && strippedTitle != title) {
            Log.d(FASELHD_TAG, "[search ] retry movie with stripped title '$strippedTitle'")
            faselHdSearch(strippedTitle)
                .map { it to scoreCandidate(it, title, year) }
                .filter { it.second >= MIN_SCORE_MOVIE }
                .maxByOrNull { it.second }?.first
        } else null
    if (best == null) {
        Log.d(FASELHD_TAG, "[match  ] no movie above $MIN_SCORE_MOVIE")
        return false
    }
    Log.d(FASELHD_TAG, "[match  ] WINNER ${best.url}")
    return faselHdExtractServers(best.url, subtitleCallback, callback)
}

private suspend fun faselHdResolveEpisode(
    title: String,
    season: Int,
    episode: Int,
    year: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val candidates = faselHdSearch(title)
        .map { it to scoreCandidate(it, title, year) }
        .filter { it.second >= MIN_SCORE_SERIES }

    // Retry with stripped leading article ("The Mentalist" -> "Mentalist") when
    // the initial search returns results but none score above the threshold.
    // FaselHD often indexes Arabic slugs without Latin title parts, so the
    // primary search yields candidates with near-empty latinTitle fields.
    val strippedTitle = title.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim()
    val best = candidates.maxByOrNull { it.second }?.first
        ?: if (strippedTitle.isNotBlank() && strippedTitle != title) {
            Log.d(FASELHD_TAG, "[search ] retry with stripped title '$strippedTitle'")
            faselHdSearch(strippedTitle)
                .map { it to scoreCandidate(it, title, year) }
                .filter { it.second >= MIN_SCORE_SERIES }
                .maxByOrNull { it.second }?.first
        } else null
    if (best == null) {
        Log.d(FASELHD_TAG, "[match  ] no series above $MIN_SCORE_SERIES")
        return false
    }
    Log.d(FASELHD_TAG, "[match  ] anchor ${best.url}")

    val doc = try {
        faselHdGet(best.url)
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[anchor ] failed: ${e.message}")
        return false
    }

    // Multi-season: season tabs carry window.location.href to per-season pages.
    val seasonCards = doc.select("div.seasonDiv")
    Log.d(FASELHD_TAG, "[anchor ] seasonCards=${seasonCards.size}")
    val seasonDoc = if (seasonCards.isNotEmpty()) {
        val re = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        val seasonHref = seasonCards.mapNotNull { re.find(it.attr("onclick"))?.groupValues?.get(1) }
            .getOrNull(season - 1)
        val seasonUrl = seasonHref?.let { if (it.startsWith("http")) it else "${faselHdBase()}$it" }
        if (seasonUrl != null) try {
            faselHdGet(seasonUrl)
        } catch (_: Exception) {
            doc
        } else doc
    } else doc

    val epUrl = faselHdExactEpisode(seasonDoc, episode) ?: run {
        Log.d(FASELHD_TAG, "[match  ] E$episode not found in page")
        return false
    }
    Log.d(FASELHD_TAG, "[match  ] E$episode -> $epUrl")
    return faselHdExtractServers(epUrl, subtitleCallback, callback)
}

suspend fun invokeFaselHd(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title?.trim().orEmpty()
    Log.d(FASELHD_TAG, "[invoke] title=$title year=${res.year} movie=${res.isMovie} s=${res.season} e=${res.episode}")
    StreamlyDiag.lastStage = "FaselHD: start"
    if (title.isEmpty()) return false

    var emitted = 0
    val counting: (ExtractorLink) -> Unit = { emitted++; callback(it) }

    return try {
        val ok = if (res.isMovie) {
            faselHdResolveMovie(title, res.year, subtitleCallback, counting)
        } else {
            faselHdResolveEpisode(title, res.season ?: 1, res.episode ?: 1, res.year, subtitleCallback, counting)
        }
        Log.d(FASELHD_TAG, "[done  ] emitted=$emitted ok=$ok")
        StreamlyDiag.lastStage = if (emitted > 0) "FaselHD: ok" else "FaselHD: no links"
        ok || emitted > 0
    } catch (e: Exception) {
        Log.e(FASELHD_TAG, "[invoke] failed: ${e.message}")
        StreamlyDiag.lastStage = "FaselHD: ${e.message}"
        emitted > 0
    }
}
