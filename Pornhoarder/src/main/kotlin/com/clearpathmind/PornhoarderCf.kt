package com.clearpathmind

import android.app.Activity
import android.content.Context
import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ---------------------------------------------------------------------------
// Automatic Cloudflare network layer (EgyDead/Streamly pattern).
//
// No manual settings dialog: when a challenge page (or 403/503/429) is hit,
// solve it in a hidden WebView, then retry with the clearance cookies.
// ---------------------------------------------------------------------------

private const val TAG = "PornHoarder"

object PornhoarderRuntime {
    var context: Context? = null
}

internal const val PH_CF_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** Real device WebView UA (upstream WebViewResolver: "setting user agent will
 *  make cloudflare break" — a stale hardcoded Chrome scores as a bot). Fetched
 *  once on the UI thread; PH_CF_UA stays as fallback until it lands. */
@Volatile
private var phRealUa: String? = null

internal fun phEffectiveUa(): String = phRealUa ?: PH_CF_UA

internal fun phRefreshRealUa() {
    if (phRealUa != null) return
    val ctx = PornhoarderRuntime.context ?: return
    runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val wv = android.webkit.WebView(ctx)
                phRealUa = wv.settings.userAgentString?.takeIf { it.isNotBlank() }
                runCatching { wv.destroy() }
                Log.d(TAG, "[cf     ] real WebView UA: $phRealUa")
            }
        }
    }
}

/** HTTP codes that mean Cloudflare / rate-limit wall. */
private val PH_CF_BLOCK_CODES = listOf(403, 503, 429)

internal fun phCfCookies(url: String): String =
    runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")

internal fun isPhCfChallenge(text: String): Boolean =
    text.contains("just a moment", ignoreCase = true) ||
        text.contains("checking your browser", ignoreCase = true) ||
        text.contains("verify you are human", ignoreCase = true) ||
        text.contains("verifying you are human", ignoreCase = true) ||
        text.contains("i'm not a robot", ignoreCase = true) ||
        text.contains("challenge-platform", ignoreCase = true) ||
        text.contains("cf-chl", ignoreCase = true) ||
        text.contains("turnstile", ignoreCase = true)

/** One lock per host: solves on different mirrors proceed in parallel while
 *  same-host solves still serialize (CF rate-limits per host). */
private val phCfHostLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

private fun phCfLockFor(url: String): Mutex {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrDefault("global")
    return phCfHostLocks.getOrPut(host) { Mutex() }
}

internal suspend fun phCfSolve(url: String): SolverResult? {
    val activity = PornhoarderRuntime.context as? Activity
    if (activity == null) {
        Log.w(TAG, "[cf     ] no Activity context available, skipping WebView solver for $url")
        return null
    }
    phRefreshRealUa()
    return phCfLockFor(url).withLock { CloudflareSolver.solve(activity, url, phEffectiveUa()) }
}

internal fun phCfHeaders(
    url: String,
    referer: String?,
    extra: Map<String, String>,
): MutableMap<String, String> = extra.toMutableMap().apply {
    putIfAbsent("User-Agent", phEffectiveUa())
    // Full browser header set: Cloudflare bot score penalizes missing
    // Accept/Accept-Language, so a bare OkHttp retry keeps failing the
    // challenge even with a valid cf_clearance while the WebView passes.
    putIfAbsent("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
    putIfAbsent("Accept-Language", "en-US,en;q=0.9")
    putIfAbsent("Upgrade-Insecure-Requests", "1")
    putIfAbsent("sec-ch-ua", "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"")
    putIfAbsent("sec-ch-ua-mobile", "?1")
    putIfAbsent("sec-ch-ua-platform", "\"Android\"")
    putIfAbsent("sec-fetch-site", "none")
    putIfAbsent("sec-fetch-mode", "navigate")
    putIfAbsent("sec-fetch-dest", "document")
    putIfAbsent("priority", "u=0, i")
    putIfAbsent("Cache-Control", "no-cache")
    putIfAbsent("Pragma", "no-cache")
    val persisted = CloudflareSolver.storedCookies()
    if (persisted.isNotBlank()) putIfAbsent("Cookie", persisted)
    val c = phCfCookies(url)
    if (c.isNotBlank()) {
        val merged = if (persisted.isNotBlank()) "$persisted; $c" else c
        put("Cookie", merged)
    }
    if (referer != null) put("Referer", referer)
}

/** Post-solve retries get a longer window: the origin is slower to answer
 *  once challenged than on a clean fetch. */
private fun phCfRetryTimeout(timeout: Long): Long = maxOf(timeout, 30000)

/** Use the solver's rendered DOM when it solved this exact page (avoids a
 *  redundant OkHttp round-trip whose bridged clearance CF may reject). */
private fun phTakeSolverHtml(solved: SolverResult, url: String): String? {
    val html = solved.html
    if (html.isNullOrBlank() || isPhCfChallenge(html)) return null
    // Captured too early (cookie set before real navigation) the DOM is an
    // empty shell — never accept it, fall through to the refetch instead.
    if (html.length < 500) {
        Log.d(TAG, "[cfGet  ] solved DOM trivial (${html.length} chars), refetching")
        return null
    }
    val same = runCatching {
        val a = java.net.URI(solved.finalUrl)
        val b = java.net.URI(url)
        a.host.equals(b.host, ignoreCase = true) && (a.path ?: "") == (b.path ?: "")
    }.getOrDefault(false)
    if (!same) {
        Log.d(TAG, "[cfGet  ] solved page differs ($url vs ${solved.finalUrl}), refetching")
        return null
    }
    return html
}

/** Hosts drift across solves (CF redirect chains land the solver on a live
 *  mirror); clearance cookies are host-scoped, so retrying at the stale
 *  origin goes out naked and hits the wall again. Remember working hosts per
 *  process and retry where solved. */
private val phHostOverride = java.util.concurrent.ConcurrentHashMap<String, String>()

internal fun phApplyHostOverride(url: String): String {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return url
    val better = phHostOverride[host] ?: return url
    if (better.equals(host, ignoreCase = true)) return url
    return runCatching {
        val u = java.net.URI(url)
        java.net.URI(u.scheme, u.userInfo, better, u.port, u.path, u.query, u.fragment).toString()
    }.getOrDefault(url)
}

/** Retry target after a solve: same path/query on the solver's landing host
 *  when it moved, remembering the move for later calls. */
internal fun phSolvedRetryUrl(solved: SolverResult, url: String): String {
    val newHost = runCatching { java.net.URI(solved.finalUrl).host }.getOrNull()
    val oldHost = runCatching { java.net.URI(url).host }.getOrNull()
    if (newHost.isNullOrBlank() || oldHost.isNullOrBlank() || newHost.equals(oldHost, ignoreCase = true)) return url
    runCatching { phHostOverride[oldHost.lowercase()] = newHost }
    Log.d(TAG, "[cfGet  ] solver moved $oldHost -> $newHost, retrying there")
    return runCatching {
        val u = java.net.URI(url)
        java.net.URI(u.scheme, u.userInfo, newHost, u.port, u.path, u.query, u.fragment).toString()
    }.getOrDefault(url)
}

internal suspend fun phCfGetText(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): String {
    val target = phApplyHostOverride(url)
    val first = runCatching {
        app.get(target, referer = referer, headers = phCfHeaders(target, referer, headers), timeout = timeout, allowRedirects = true, cacheTime = 0)
    }.getOrNull()
    // Explicit String? type: app response accessors carry a jspecify
    // @Nullable annotation that isn't on the compile classpath.
    val firstText: String? = first?.text
    if (first != null && first.code !in PH_CF_BLOCK_CODES && firstText != null && !isPhCfChallenge(firstText)) {
        return firstText
    }
    // Stage 2: one silent plain retry before burning a WebView solve — CF
    // sometimes passes the second hit.
    delay(1500)
    runCatching {
        app.get(target, referer = referer, headers = phCfHeaders(target, referer, headers), timeout = timeout, allowRedirects = true, cacheTime = 0)
    }.getOrNull()?.let { pre ->
        val preText: String? = pre.text
        if (pre.code !in PH_CF_BLOCK_CODES && preText != null && !isPhCfChallenge(preText)) {
            Log.d(TAG, "[cfGet  ] wall cleared on silent retry for $target")
            return preText
        }
    }
    Log.d(TAG, "[cfGet  ] wall ($target code=${first?.code}), solving…")
    val solved = phCfSolve(target)
    val retryUrl = if (solved != null) phSolvedRetryUrl(solved, target) else target
    if (solved != null) {
        phTakeSolverHtml(solved, retryUrl)?.let { html ->
            Log.d(TAG, "[cfGet  ] solved-page DOM for $retryUrl (${html.length} chars)")
            return html
        }
    } else if (firstText != null) {
        return firstText
    }
    val ck = phCfCookies(retryUrl)
    val secondResp = runCatching {
        app.get(retryUrl, referer = referer, headers = phCfHeaders(retryUrl, referer, headers), timeout = phCfRetryTimeout(timeout), allowRedirects = true, cacheTime = 0)
    }.getOrNull()
    val second: String? = secondResp?.text
    Log.d(TAG, "[cfGet  ] retry $retryUrl code=${secondResp?.code} clearance=${ck.contains("cf_clearance")} challenge=${second?.let { isPhCfChallenge(it) }}")
    return second ?: firstText ?: ""
}

internal suspend fun phCfPostForm(
    url: String,
    body: FormBody,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): String {
    val target = phApplyHostOverride(url)
    val first = runCatching {
        app.post(target, requestBody = body, referer = referer, headers = phCfHeaders(target, referer, headers), timeout = timeout).text
    }.getOrNull()
    if (first != null && !isPhCfChallenge(first)) return first
    // Stage 2: one silent plain retry before solving.
    delay(1500)
    runCatching {
        app.post(target, requestBody = body, referer = referer, headers = phCfHeaders(target, referer, headers), timeout = timeout).text
    }.getOrNull()?.let { pre ->
        if (!isPhCfChallenge(pre)) {
            Log.d(TAG, "[cfPost ] wall cleared on silent retry for $target")
            return pre
        }
    }
    // Challenge on a POST: solve, then retry with the freshly stored clearance cookie.
    Log.d(TAG, "[cfPost ] wall ($target), solving…")
    val solved = phCfSolve(target)
    val retryUrl = if (solved != null) phSolvedRetryUrl(solved, target) else target
    val ck = phCfCookies(retryUrl)
    val retry: String? = runCatching {
        app.post(retryUrl, requestBody = body, referer = referer, headers = phCfHeaders(retryUrl, referer, headers), timeout = phCfRetryTimeout(timeout)).text
    }.getOrNull()
    Log.d(TAG, "[cfPost ] retry $retryUrl clearance=${ck.contains("cf_clearance")} challenge=${retry?.let { isPhCfChallenge(it) }}")
    return retry ?: first ?: ""
}

internal suspend fun phCfPostDoc(
    url: String,
    body: FormBody,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 15000,
): Document = Jsoup.parse(phCfPostForm(url, body, referer, headers, timeout), url)
