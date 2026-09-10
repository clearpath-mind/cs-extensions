package com.streamly.settings

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

/**
 * Manual Cloudflare challenge solver for EgyDead.
 *
 * Solves run automatically in the background, but on networks where the
 * challenge platform itself is unreachable they can stall forever. This
 * screen lets you solve it by hand in a visible WebView: cookies live in the
 * shared CookieManager, so saving here applies instantly to EgyDead
 * (no extra persistence needed).
 */
class EgyDeadCfFragment : DialogFragment() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null

    companion object {
        private const val TAG = "EgyDeadCF"
        const val SITE_URL = "https://egydead.beer"
        private const val COLOR_BG = "#121212"
        private const val COLOR_CARD = "#1C1C22"
        private const val COLOR_ACCENT = "#1F6FEB"
        private const val COLOR_GREEN = "#4CAF50"
        private const val COLOR_AMBER = "#FFC107"
        private const val COLOR_GRAY = "#BDBDBD"

        private const val CHECK_CHALLENGE_JS =
            "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                "||h.includes(\"checking your browser\")||h.includes(\"just a moment\")" +
                "||h.includes(\"performing security verification\");})();"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val title = TextView(ctx).apply {
            text = "EgyDead"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        val subtitle = TextView(ctx).apply {
            text = "Solve the Cloudflare challenge below, then Save & Close"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 16)
        }
        val status = TextView(ctx).apply {
            text = "Loading…"
            setTextColor(Color.parseColor(COLOR_GRAY))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 28)
            background = cardDrawable(Color.parseColor(COLOR_CARD))
        }
        statusView = status
        val wv = WebView(ctx)
        // Fill leftover dialog space but never collapse: weight for flow,
        // minimum height as a floor, rounded outline to sit inside the dialog.
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
        val save = actionButton("SAVE & CLOSE", COLOR_GREEN) {
            try {
                CookieManager.getInstance().flush()
                Log.d(TAG, "cookies saved for ${wv.url}")
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

    /** Rounded button with white-stroke focused state for TV/DPAD users. */
    private fun actionButton(
        label: String,
        colorHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(requireContext()).apply {
            text = label
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
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
                userAgentString = com.streamly.CF_UA
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
        // Challenge widgets sometimes open a popup window; keep it in place.
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
        wv.loadUrl(SITE_URL)
        dialog?.setOnShowListener { wv.requestFocus() }
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

    /** Passive monitor: cookies are shared, so solving here applies instantly.
     *  Auto-closes shortly after clearance is detected. */
    private fun startDetection(webView: WebView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                try {
                    val currentUrl = try {
                        webView.url ?: SITE_URL
                    } catch (_: Exception) {
                        SITE_URL
                    }
                    val cookies = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
                    if (cookies.contains("cf_clearance")) {
                        setStatus("Solved ✓ — saving & closing…", COLOR_GREEN)
                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Exception) {
                        }
                        Log.d(TAG, "clearance captured for $currentUrl")
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
                                    "No challenge on this page — Save & Close, then try EgyDead.",
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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        stopDetection()
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        statusView = null
    }
}
