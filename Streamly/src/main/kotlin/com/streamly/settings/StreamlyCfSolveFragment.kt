package com.streamly.settings

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.streamly.CF_UA
import com.streamly.faselHdSolveUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual Cloudflare solve dialog for FaselHD only.
 *
 * The hidden auto-solver (CloudflareSolver) can't always clear FaselHD's
 * Turnstile challenge, so this opens a visible, touchable WebView on the
 * FaselHD origin where the user solves it by hand. The resulting
 * cf_clearance cookie stays in [CookieManager], which the shared CF network
 * layer (cfCookies) already attaches to every subsequent request — nothing
 * else needs to change. Auto-solve remains the first try at request time.
 */
class StreamlyCfSolveFragment(
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Runnable? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var solveUrl: String = ""

    companion object {
        const val COLOR_BG = "#121212"
        const val COLOR_CARD = "#1C1C22"
        const val COLOR_ACCENT = "#FF9800"
        const val COLOR_FOCUS = "#3A3A44"
        const val COLOR_GREEN = "#4CAF50"
        const val COLOR_AMBER = "#FFC107"
        const val COLOR_GRAY = "#BDBDBD"

        private const val CHECK_CHALLENGE_JS =
            "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                "||h.includes(\"checking your browser\")||h.includes(\"just a moment\");})();"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val title = TextView(ctx).apply {
            text = "FaselHD"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        val subtitle = TextView(ctx).apply {
            text = "Solve the challenge below, then Save & Close"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 16)
        }
        val status = TextView(ctx).apply {
            text = "Resolving FaselHD mirror…"
            setTextColor(Color.parseColor(COLOR_GRAY))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 28)
            background = cardDrawable(Color.parseColor(COLOR_CARD))
        }
        statusView = status
        val wv = WebView(ctx)
        wv.minimumHeight = (240 * ctx.resources.displayMetrics.density).toInt()
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            setMargins(12, 0, 12, 0)
        }
        try {
            wv.outlineProvider = ViewOutlineProvider.BACKGROUND
            wv.clipToOutline = true
            wv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.BLACK)
                cornerRadius = 16f
            }
        } catch (_: Exception) {
        }
        webView = wv

        val reload = actionButton("RELOAD", COLOR_CARD) {
            setStatus("Reloading…", COLOR_GRAY)
            wv.reload()
        }
        val save = actionButton("SAVE & CLOSE", COLOR_ACCENT, darkText = true) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
            dismissAllowingStateLoss()
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 20)
            addView(reload, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = 20
            })
            addView(save, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = 20
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                subtitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(20, 0, 20, 8) }
            )
            addView(wv)
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dp(v: Int): Int =
        (v * (activity?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private fun cardDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = 16f
    }

    private fun actionButton(
        label: String,
        colorHex: String,
        darkText: Boolean = false,
        onClick: () -> Unit
    ): Button {
        return Button(requireContext()).apply {
            text = label
            setTextColor(if (darkText) Color.parseColor("#101014") else Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor(COLOR_FOCUS))
                    cornerRadius = 16f
                    setStroke(4, Color.WHITE)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
                    cornerRadius = 16f
                })
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            activity?.let {
                val dm = it.resources.displayMetrics
                setLayout((dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.88).toInt())
            }
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_BG))
                cornerRadius = 32f
                setStroke(3, Color.parseColor(COLOR_ACCENT))
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val wv = webView ?: return
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                builtInZoomControls = true
                displayZoomControls = false
                setNeedInitialFocus(true)
                userAgentString = CF_UA
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            wv.isFocusable = true
            wv.isFocusableInTouchMode = true
            wv.setBackgroundColor(Color.BLACK)
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
        } catch (_: Exception) {
        }
        // Turnstile sometimes opens a popup window; keep it in the same view.
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
        // TV/DPAD: synthesize clicks on the focused element.
        wv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                wv.evaluateJavascript(
                    """(function(){var el=document.activeElement;if(!el)return;
                    |var o={bubbles:true,cancelable:true,view:window};
                    |el.dispatchEvent(new MouseEvent('mousedown',o));
                    |el.dispatchEvent(new MouseEvent('mouseup',o));
                    |el.dispatchEvent(new MouseEvent('click',o));})();""".trimMargin(),
                    null
                )
                true
            } else false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus("Page loaded — checking for challenge…", COLOR_GRAY)
                startDetection(wv)
            }
        }
        // Resolve the live mirror first so the clearance cookie lands on the
        // same host the provider requests hit.
        scope.launch {
            val url = try {
                withContext(Dispatchers.IO) { faselHdSolveUrl() }
            } catch (_: Exception) {
                null
            }
            solveUrl = url ?: "https://www.fasel-hd.co/"
            try {
                wv.loadUrl(solveUrl)
            } catch (_: Exception) {
                setStatus("Failed to load — Reload and try again.", COLOR_AMBER)
            }
            dialog?.setOnShowListener { wv.requestFocus() }
        }
    }

    private fun setStatus(text: String, colorHex: String) {
        statusView?.let {
            it.text = text
            try {
                it.setTextColor(Color.parseColor(colorHex))
            } catch (_: Exception) {
            }
        }
    }

    /** Passive challenge monitor: auto Save & Close shortly after clearance. */
    private fun startDetection(webView: WebView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                try {
                    val target = solveUrl.ifBlank { webView.url.orEmpty() }
                    val cookies = if (target.isNotBlank()) {
                        runCatching { CookieManager.getInstance().getCookie(target).orEmpty() }.getOrDefault("")
                    } else ""
                    if (cookies.contains("cf_clearance")) {
                        setStatus("Solved \u2713 — saving & closing…", COLOR_GREEN)
                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Exception) {
                        }
                        stopDetection()
                        uiHandler.postDelayed({ dismissAllowingStateLoss() }, 1200)
                        return
                    }
                    webView.evaluateJavascript(CHECK_CHALLENGE_JS) { res ->
                        try {
                            val challenged = res?.contains("true") == true
                            if (challenged) {
                                setStatus(
                                    "Challenge detected — tap the checkbox in the page above.",
                                    COLOR_AMBER
                                )
                            } else {
                                setStatus(
                                    "No challenge on this page — Save & Close, then try FaselHD.",
                                    COLOR_GRAY
                                )
                            }
                        } catch (_: Exception) {
                        }
                        poll = this
                        uiHandler.postDelayed(this, 2500)
                    }
                } catch (_: Exception) {
                    poll = this
                    uiHandler.postDelayed(this, 2500)
                }
            }
        }
        uiHandler.postDelayed(task, 1500)
    }

    private fun stopDetection() {
        try {
            poll?.let { uiHandler.removeCallbacks(it) }
        } catch (_: Exception) {
        }
        poll = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            scope.cancel()
        } catch (_: Exception) {
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        stopDetection()
        try {
            scope.cancel()
        } catch (_: Exception) {
        }
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        statusView = null
        onDismissCallback?.invoke()
    }
}
