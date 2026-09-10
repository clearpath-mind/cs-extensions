package com.streamly

import android.R
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Clearance result: final URL (follows mirror rotations) + cookies for that URL
 *  + the solved page's rendered HTML (avoids a redundant refetch). */
data class SolverResult(val finalUrl: String, val cookies: String?, val html: String? = null)

private const val READINESS_JS =
    """(function(){try{
        var html = document.documentElement.innerHTML || "";
        var t = document.body ? document.body.innerText : "";
        var ch = /just a moment|checking your browser|verify you are human|performing security verification/i.test(html);
        return document.readyState + "|" + t.length + "|" + ch + "|" + location.href;
    }catch(e){ return "loading|0|true|"; }})();"""

private fun cleanJs(html: String?): String? = html?.removeSurrounding("\"")
    ?.replace("\\u003C", "<")
    ?.replace("\\u003E", ">")
    ?.replace("\\\"", "\"")
    ?.replace("\\\\", "\\")

private fun newHiddenWebView(activity: Activity, userAgent: String): WebView? = try {
    WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        alpha = 0.01f
        translationX = 10000f
        translationY = 10000f
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = userAgent
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }
} catch (_: Exception) {
    null
}

object CloudflareSolver {
    private const val TAG = "CF_Solver_Hidden"

    suspend fun solve(activity: Activity?, initialUrl: String, userAgent: String): SolverResult? {
        return suspendCoroutine { continuation ->
            if (activity == null || activity.isFinishing) {
                continuation.resume(null)
                return@suspendCoroutine
            }

            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run {
                    continuation.resume(null)
                    return@post
                }

                val webView = try {
                    WebView(activity)
                } catch (e: Exception) {
                    Log.w(TAG, "no WebView on device: ${e.message}")
                    continuation.resume(null)
                    return@post
                }
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                webView.alpha = 0.01f
                webView.translationX = 10000f
                webView.translationY = 10000f
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.isClickable = false

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    this.userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false
                var isProcessingClick = false
                var bypassWatching = false
                val pollingHandler = Handler(Looper.getMainLooper())

                fun finishSuccess(finalUrl: String, reason: String = "unknown", awaitContent: Boolean = true) {
                    if (isSolved) return
                    isSolved = true
                    Log.i(TAG, "clearance | reason: $reason | finalUrl: $finalUrl")

                    cookieManager.flush()
                    pollingHandler.removeCallbacksAndMessages(null)

                    var delivered = false
                    fun deliver(html: String?) {
                        if (delivered) return
                        delivered = true
                        var cleanHtml = html?.removeSurrounding("\"")
                            ?.replace("\\u003C", "<")
                            ?.replace("\\u003E", ">")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\")
                        val finalCookies = runCatching { cookieManager.getCookie(finalUrl) }.getOrNull()
                        if (finalCookies.isNullOrBlank()) {
                            Log.w(TAG, "finished without cookies for $finalUrl")
                        }
                        try {
                            rootView.removeView(webView)
                            webView.destroy()
                        } catch (e: Exception) {}

                        continuation.resume(SolverResult(finalUrl, finalCookies, cleanHtml))
                    }

                    if (!awaitContent) {
                        deliver(null)
                        return
                    }

                    // Phase 2: Cloudflare sets cf_clearance BEFORE navigating to the
                    // real page — capturing outerHTML immediately yields an empty
                    // shell. Wait for rendered content instead.
                    var lastUrl: String? = null
                    var stableSince = SystemClock.uptimeMillis()
                    pollingHandler.postDelayed({ deliver(null) }, 30000)
                    fun poll() {
                        if (delivered) return
                        val js = """
                            (function(){try{
                                var html = document.documentElement.innerHTML || "";
                                var t = document.body ? document.body.innerText : "";
                                var ch = /just a moment|checking your browser|verify you are human|performing security verification/i.test(html);
                                return document.readyState + "|" + t.length + "|" + ch + "|" + location.href;
                            }catch(e){ return "loading|0|true|"; }})();
                        """.trimIndent()
                        try {
                            webView.evaluateJavascript(js) { res ->
                                if (delivered) return@evaluateJavascript
                                val parts = res?.removeSurrounding("\"")?.split("|") ?: emptyList()
                                if (parts.size < 4) {
                                    pollingHandler.postDelayed({ poll() }, 500)
                                    return@evaluateJavascript
                                }
                                val ready = parts[0]
                                val textLen = parts[1].toIntOrNull() ?: 0
                                val challenged = parts[2].toBoolean()
                                val currentUrl = parts[3]
                                val now = SystemClock.uptimeMillis()
                                if (currentUrl != lastUrl) {
                                    lastUrl = currentUrl
                                    stableSince = now
                                }
                                if (ready == "complete" && !challenged && textLen > 500 && now - stableSince > 1500) {
                                    webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                        deliver(html)
                                    }
                                } else {
                                    pollingHandler.postDelayed({ poll() }, 500)
                                }
                            }
                        } catch (e: Exception) {
                            deliver(null)
                        }
                    }
                    poll()
                }

                pollingHandler.postDelayed({
                    finishSuccess(webView.url ?: initialUrl, "Timeout - 60s")
                }, 60000)

                fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
                    val density = activity.resources.displayMetrics.density
                    val realX = cssX * density
                    val realY = cssY * density
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis() + 50
                    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
                    view.dispatchTouchEvent(downEvent)
                    view.postDelayed({
                        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, realX, realY, 0)
                        view.dispatchTouchEvent(upEvent)
                        downEvent.recycle()
                        upEvent.recycle()
                    }, 50)
                }

                val targetCssPath = "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"

                fun startPolling() {
                    val runnable = object : Runnable {
                        override fun run() {
                            if (isSolved || isProcessingClick) {
                                pollingHandler.postDelayed(this, 2000)
                                return
                            }

                            val jsGetCoords = """
                                (function(){
                                    try{
                                        var box = document.querySelector("$targetCssPath");
                                        if(!box) return "NO_BOX";
                                        var r = box.getBoundingClientRect();
                                        if(r.width === 0 && r.height === 0) return "NO_BOX";
                                        var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                                        var margin = Math.round(Math.max(8, r.width * 0.03));
                                        var centerY = r.top + (r.height / 2);
                                        var rightSideX = r.right - (size / 2) - margin;
                                        var leftSideX = r.left + (size / 2) + margin;
                                        return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
                                    }catch(e){ return "ERROR"; }
                                })();
                            """.trimIndent()

                            webView.evaluateJavascript(jsGetCoords) { res ->
                                try {
                                    val clean = res?.removeSurrounding("\"")
                                    if (clean != null && clean.contains("|")) {
                                        isProcessingClick = true
                                        val sides = clean.split("|")
                                        val (rx, ry) = sides[0].split(",").map { it.toFloatOrNull() }
                                        val (lx, ly) = sides[1].split(",").map { it.toFloatOrNull() }
                                        if (rx != null && ry != null && lx != null && ly != null) {
                                            simulateRealTouch(webView, rx, ry)
                                            pollingHandler.postDelayed({
                                                simulateRealTouch(webView, lx, ly)
                                                pollingHandler.postDelayed({ isProcessingClick = false }, 3000)
                                            }, 250)
                                        } else { isProcessingClick = false }
                                    }
                                } catch (e: Exception) { isProcessingClick = false }
                            }
                            pollingHandler.postDelayed(this, 2000)
                        }
                    }
                    pollingHandler.post(runnable)
                }

                fun checkBypassSuccess() {
                    if (isSolved) return
                    if (bypassWatching) return
                    bypassWatching = true

                    fun poll() {
                        if (isSolved) {
                            bypassWatching = false
                            return
                        }
                        val currentLiveUrl = try {
                            webView.url ?: initialUrl
                        } catch (_: Exception) {
                            initialUrl
                        }
                        val currentCookies = runCatching { cookieManager.getCookie(currentLiveUrl) }.getOrNull()

                        if (currentCookies != null && currentCookies.contains("cf_clearance")) {
                            bypassWatching = false
                            finishSuccess(currentLiveUrl, "cf_clearance captured")
                            return
                        }

                        pollingHandler.postDelayed({ poll() }, 500)
                    }
                    poll()
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null && url != initialUrl) {
                            Log.d(TAG, "redirect to: $url")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        val failing = request?.url?.toString()
                        Log.w(TAG, "load error on $failing: ${error?.description}")
                        // Dead seed domain (DNS/ISP block): don't burn the full
                        // 60s — bail out so the caller can try the next mirror.
                        if (request?.isForMainFrame == true && failing == initialUrl) {
                            pollingHandler.postDelayed({
                                if (!isSolved) finishSuccess(initialUrl, "load-error", awaitContent = false)
                            }, 5000)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isProcessingClick = false
                        startPolling()
                        checkBypassSuccess()
                    }
                }

                rootView.addView(webView)
                webView.loadUrl(initialUrl)
                // Challenges that never fire onPageFinished still get watched.
                checkBypassSuccess()
            }
        }
    }

    /**
     * WebView-native GET: some hosts re-challenge non-browser TLS stacks even
     * with valid clearance, so OkHttp can never pass. Load the page in a hidden
     * WebView (shares the cleared CookieManager) and return its rendered HTML.
     */
    suspend fun fetchPage(
        activity: Activity?,
        url: String,
        userAgent: String,
        timeoutMs: Long = 45000,
    ): String? {
        if (activity == null || activity.isFinishing) return null
        return suspendCoroutine { continuation ->
            var done = false
            fun finish(html: String?) {
                if (done) return
                done = true
                continuation.resume(html)
            }
            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run { finish(null); return@post }
                val webView = newHiddenWebView(activity, userAgent) ?: run { finish(null); return@post }
                val pollingHandler = Handler(Looper.getMainLooper())
                var lastUrl: String? = null
                var stableSince = SystemClock.uptimeMillis()
                fun cleanup(html: String?) {
                    runCatching {
                        pollingHandler.removeCallbacksAndMessages(null)
                        rootView.removeView(webView)
                        webView.destroy()
                    }
                    finish(cleanJs(html))
                }
                pollingHandler.postDelayed({ cleanup(null) }, timeoutMs)
                fun poll() {
                    try {
                        webView.evaluateJavascript(READINESS_JS) { res ->
                            val parts = res?.removeSurrounding("\"")?.split("|") ?: emptyList()
                            if (parts.size < 4) {
                                pollingHandler.postDelayed({ poll() }, 500)
                                return@evaluateJavascript
                            }
                            val ready = parts[0]
                            val textLen = parts[1].toIntOrNull() ?: 0
                            val challenged = parts[2].toBoolean()
                            val cur = parts[3]
                            val now = SystemClock.uptimeMillis()
                            if (cur != lastUrl) {
                                lastUrl = cur
                                stableSince = now
                            }
                            if (ready == "complete" && !challenged && textLen > 500 && now - stableSince > 1500) {
                                webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    cleanup(html)
                                }
                            } else {
                                pollingHandler.postDelayed({ poll() }, 500)
                            }
                        }
                    } catch (_: Exception) {
                        cleanup(null)
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        poll()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && request.url?.toString() == url) {
                            pollingHandler.postDelayed({ cleanup(null) }, 4000)
                        }
                    }
                }
                rootView.addView(webView)
                webView.loadUrl(url)
                pollingHandler.postDelayed({ poll() }, 3000)
            }
        }
    }

    /**
     * WebView-native POST: runs fetch() inside a same-origin page context so
     * cookies + TLS stack match a real browser. Returns the response text.
     */
    suspend fun postPage(
        activity: Activity?,
        url: String,
        data: Map<String, String>,
        referer: String?,
        userAgent: String,
        timeoutMs: Long = 45000,
    ): String? {
        if (activity == null || activity.isFinishing) return null
        return suspendCoroutine { continuation ->
            var done = false
            fun finish(text: String?) {
                if (done) return
                done = true
                val clean = cleanJs(text)
                continuation.resume(clean?.takeUnless { it.startsWith("__ERR__") })
            }
            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run { finish(null); return@post }
                val webView = newHiddenWebView(activity, userAgent) ?: run { finish(null); return@post }
                val pollingHandler = Handler(Looper.getMainLooper())
                var lastUrl: String? = null
                var stableSince = SystemClock.uptimeMillis()
                fun cleanup(text: String?) {
                    runCatching {
                        pollingHandler.removeCallbacksAndMessages(null)
                        rootView.removeView(webView)
                        webView.destroy()
                    }
                    finish(text)
                }
                pollingHandler.postDelayed({ cleanup(null) }, timeoutMs)
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                fun firePost() {
                    val body = data.entries.joinToString("&") { (k, v) ->
                        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                    }
                    val js = """(function(){return fetch("${esc(url)}",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8","X-Requested-With":"XMLHttpRequest"},body:"${esc(body)}",credentials:"include"}).then(function(r){return r.text();}).then(function(t){return t.slice(0,500000);}).catch(function(e){return "__ERR__"+e;});})();"""
                    try {
                        webView.evaluateJavascript(js) { res -> cleanup(res) }
                    } catch (_: Exception) {
                        cleanup(null)
                    }
                }
                fun poll() {
                    try {
                        webView.evaluateJavascript(READINESS_JS) { res ->
                            val parts = res?.removeSurrounding("\"")?.split("|") ?: emptyList()
                            if (parts.size < 4) {
                                pollingHandler.postDelayed({ poll() }, 500)
                                return@evaluateJavascript
                            }
                            val ready = parts[0]
                            val textLen = parts[1].toIntOrNull() ?: 0
                            val challenged = parts[2].toBoolean()
                            val cur = parts[3]
                            val now = SystemClock.uptimeMillis()
                            if (cur != lastUrl) {
                                lastUrl = cur
                                stableSince = now
                            }
                            if (ready == "complete" && !challenged && textLen > 500 && now - stableSince > 1500) {
                                firePost()
                            } else {
                                pollingHandler.postDelayed({ poll() }, 500)
                            }
                        }
                    } catch (_: Exception) {
                        cleanup(null)
                    }
                }
                val contextUrl = referer ?: url
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        poll()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && request.url?.toString() == contextUrl) {
                            pollingHandler.postDelayed({ cleanup(null) }, 4000)
                        }
                    }
                }
                rootView.addView(webView)
                webView.loadUrl(contextUrl)
                pollingHandler.postDelayed({ poll() }, 3000)
            }
        }
    }
}
