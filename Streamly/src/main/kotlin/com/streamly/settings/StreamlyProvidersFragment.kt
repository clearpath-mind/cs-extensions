package com.streamly.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.streamly.BuildConfig
import com.streamly.LinkData
import com.streamly.Provider
import com.streamly.ProvidersList
import com.streamly.StreamlyDiag
import com.streamly.StreamlyPlugin
import com.streamly.StreamlyRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Source test states, ported from StreamPlay's providers settings. */
enum class ProviderStatus {
    UNTESTED,
    TESTING,
    ONLINE,
    OFFLINE,
}

class StreamlyProvidersFragment(
    private val plugin: StreamlyPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private lateinit var btnSave: ImageButton
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var adapter: ProviderAdapter
    private lateinit var container: LinearLayout
    private var providers: List<Provider> = emptyList()
    private val PREFS_DISABLED = "disabled_providers"
    private val PREFS_TEST_STATUS = "provider_test_status"
    private lateinit var tvProviderCount: TextView

    private val providerStatuses = mutableMapOf<String, ProviderStatus>()
    private val testScope: CoroutineScope = MainScope()
    private val categoryHeaders = mutableMapOf<String, TextView>()

    /** Known-good titles used as test probes (movie + show, TMDB + IMDb keyed). */
    private val testCandidates = listOf(
        LinkData(id = 693134, type = "movie", title = "Dune: Part Two", year = 2024, imdbId = "tt15239678"),
        LinkData(id = 550, type = "movie", title = "Fight Club", year = 1999, imdbId = "tt0137523"),
        LinkData(id = 27205, type = "movie", title = "Inception", year = 2010, imdbId = "tt1375666"),
        LinkData(id = 1396, type = "tv", title = "Breaking Bad", year = 2008, season = 1, episode = 1, imdbId = "tt0903747"),
    )

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun createStatusBadge(color: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(color)
        }
    }

    private fun setStatusView(tv: TextView, status: ProviderStatus, detail: String? = null) {
        when (status) {
            ProviderStatus.ONLINE -> {
                tv.text = "● ONLINE"
                tv.background = createStatusBadge(android.graphics.Color.parseColor("#2E7D32"))
                tv.visibility = View.VISIBLE
            }
            ProviderStatus.OFFLINE -> {
                // Surface the failing stage (e.g. "no links") so a red badge
                // is diagnosable without logcat. StreamlyDiag.lastStage is
                // written by every provider invoke as "<Name>: <stage>".
                val reason = detail?.trim().orEmpty()
                    .let { if (":" in it) it.substringAfter(":").trim() else it }
                    .take(24).trim()
                tv.text = if (reason.isNotEmpty() && reason != "start") "● OFFLINE · $reason" else "● OFFLINE"
                tv.background = createStatusBadge(android.graphics.Color.parseColor("#C62828"))
                tv.visibility = View.VISIBLE
            }
            ProviderStatus.TESTING -> {
                tv.text = "TESTING..."
                tv.background = createStatusBadge(android.graphics.Color.parseColor("#EF6C00"))
                tv.visibility = View.VISIBLE
            }
            ProviderStatus.UNTESTED -> {
                tv.visibility = View.GONE
            }
        }
    }

    private fun saveTestStatuses() {
        // Never persist OFFLINE: a red badge from one bad night (rotated
        // upstream, slow network) must not stick across restarts. ONLINE
        // persists; anything else re-tests as UNTESTED.
        val encoded = providerStatuses.entries
            .filter { it.value == ProviderStatus.ONLINE }
            .joinToString("|") { "${it.key}:${it.value.name}" }
        sharedPref.edit { putString(PREFS_TEST_STATUS, encoded) }
    }

    private fun loadTestStatuses() {
        val raw = sharedPref.getString(PREFS_TEST_STATUS, null) ?: return
        raw.split("|").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                runCatching { ProviderStatus.valueOf(parts[1]) }.getOrNull()
                    ?.takeIf { it == ProviderStatus.ONLINE }
                    ?.let { providerStatuses[parts[0]] = it }
            }
        }
    }

    /** Embed-chain providers (2Embed -> Uqload -> m3u8) need room: the real
     * loadLinks path gives them 90s+ while the old 25s probe budget timed
     * out healthy runs and reported OFFLINE. */
    private fun probeBudgetMs(provider: Provider): Long =
        if (provider.id == "2embed") 60000L else 25000L

    /**
     * Probe a provider against known-good candidates. Returns true on the
     * first candidate that yields any video link or subtitle URL (StreamPlay
     * testProvider pattern adapted to the 3-arg invoke). Dune: Part Two
     * (2024) goes first: 2Embed serves new releases on its legacy uqloads
     * path while old catalogue titles moved to vidsrc-family players.
     */
    private suspend fun testProvider(provider: Provider): Boolean = withContext(Dispatchers.IO) {
        StreamlyRuntime.context = CommonActivity.activity
        val budget = probeBudgetMs(provider)
        for (candidate in testCandidates) {
            var found = false
            val subCb: (SubtitleFile) -> Unit = { sub ->
                if (!sub.url.isNullOrBlank()) found = true
            }
            val cb: (ExtractorLink) -> Unit = { link ->
                if (link.url.isNotBlank()) found = true
            }
            runCatching {
                withTimeoutOrNull(budget) {
                    provider.invoke(candidate, subCb, cb)
                }
            }
            if (found) return@withContext true
        }
        false
    }

    private fun testSingleProvider(provider: Provider, statusView: TextView, testBtn: Button) {
        testScope.launch {
            testBtn.isEnabled = false
            providerStatuses[provider.id] = ProviderStatus.TESTING
            setStatusView(statusView, ProviderStatus.TESTING)
            val ok = testProvider(provider)
            // Capture the failing stage for the badge before anything else
            // overwrites the shared diagnostic channel.
            val stage = StreamlyDiag.lastStage
            val status = if (ok) ProviderStatus.ONLINE else ProviderStatus.OFFLINE
            providerStatuses[provider.id] = status
            setStatusView(statusView, status, if (ok) null else stage)
            saveTestStatuses()
            testBtn.isEnabled = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = getLayout("fragment_providers", inflater, container)
        val drawableId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (drawableId != 0) {
            view.background = res.getDrawable(drawableId, null)
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (500 * displayMetrics.density).toInt()
            val width = if (displayMetrics.widthPixels > 0 && displayMetrics.widthPixels > maxDialogWidth) {
                maxDialogWidth
            } else {
                (displayMetrics.widthPixels * 0.9f).toInt()
            }
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateProviderCount() {
        val enabled = providers.count { !adapter.isDisabled(it.id) }
        val total = providers.size
        tvProviderCount.text = "$enabled / $total enabled"
        // Per-category header counts (display-only, same disabled set).
        ProvidersList.categoryOrder.forEach { category ->
            val header = categoryHeaders[category] ?: return@forEach
            val inCategory = providers.filter { it.category == category }
            if (inCategory.isEmpty()) return@forEach
            val enabledInCategory = inCategory.count { !adapter.isDisabled(it.id) }
            header.text = "$category ($enabledInCategory/${inCategory.size})"
        }
    }

    private fun addCategoryHeader(category: String) {
        val density = container.context.resources.displayMetrics.density
        val header = TextView(container.context).apply {
            tag = "header_$category"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (4 * density).toInt(),
            )
        }
        categoryHeaders[category] = header
        container.addView(header)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvProviderCount = view.findView("tv_provider_count")
        btnSave = view.findView("btn_save")
        btnSave.setImageDrawable(getDrawable("save_icon"))
        btnSave.makeTvCompatible()

        btnSelectAll = view.findView("btn_select_all")
        btnDeselectAll = view.findView("btn_deselect_all")
        btnSelectAll.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF2E7D32"))
        btnDeselectAll.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFBB2D3B"))
        container = view.findView("list_container")
        container.makeTvCompatible()
        container.removeAllViews()
        categoryHeaders.clear()
        val order = ProvidersList.categoryOrder
        providers = ProvidersList.providers.sortedWith(
            compareBy({ order.indexOf(it.category).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }, { it.name.lowercase() })
        )
        loadTestStatuses()

        val savedDisabled = sharedPref.getStringSet(PREFS_DISABLED, emptySet()) ?: emptySet()

        adapter = ProviderAdapter(providers, savedDisabled) { disabled ->
            sharedPref.edit { putStringSet(PREFS_DISABLED, disabled) }
            updateUI()
            updateProviderCount()
        }

        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val statusId = res.getIdentifier("tv_provider_status", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val testId = res.getIdentifier("btn_test_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        providers.forEach { provider ->
            if (categoryHeaders[provider.category] == null) addCategoryHeader(provider.category)
            val item = getLayout("item_provider_checkbox", layoutInflater, container)
            item.tag = provider.id
            val chk = item.findViewById<CheckBox>(chkId)
            item.makeTvCompatible()
            chk.makeTvCompatible()
            chk.text = provider.name
            chk.isChecked = !adapter.isDisabled(provider.id)

            chk.setOnCheckedChangeListener { _, isChecked ->
                adapter.setDisabled(provider.id, !isChecked)
            }

            if (statusId != 0) {
                val statusView = item.findViewById<TextView>(statusId)
                if (statusView != null) {
                    setStatusView(statusView, providerStatuses[provider.id] ?: ProviderStatus.UNTESTED)
                }
            }
            if (testId != 0) {
                val testBtn = item.findViewById<Button>(testId)
                val statusView = if (statusId != 0) item.findViewById<TextView>(statusId) else null
                testBtn?.setOnClickListener {
                    if (statusView != null) testSingleProvider(provider, statusView, testBtn)
                }
            }

            container.addView(item)
            updateProviderCount()
        }

        btnSelectAll.setOnClickListener { adapter.setAll(true) }
        btnDeselectAll.setOnClickListener { adapter.setAll(false) }
        btnSave.setOnClickListener { dismissFragment() }
    }

    private fun updateUI() {
        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val statusId = res.getIdentifier("tv_provider_status", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val byId = providers.associateBy { it.id }
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            // Section headers carry a "header_<category>" tag, provider rows the provider id.
            val provider = byId[row.tag as? String] ?: continue
            row.findViewById<CheckBox>(chkId)?.let { chk ->
                chk.isChecked = !adapter.isDisabled(provider.id)
            }
            if (statusId != 0) {
                row.findViewById<TextView>(statusId)?.let { tv ->
                    setStatusView(tv, providerStatuses[provider.id] ?: ProviderStatus.UNTESTED)
                }
            }
        }
        updateProviderCount()
    }

    private fun dismissFragment() {
        // Sources just save (persisted per-toggle); no app reload here.
        dismiss()
    }

    inner class ProviderAdapter(
        private val items: List<Provider>,
        initiallyDisabled: Set<String>,
        private val onChange: (Set<String>) -> Unit
    ) {
        private val disabled = initiallyDisabled.toMutableSet()

        fun isDisabled(id: String) = id in disabled

        fun setDisabled(id: String, value: Boolean) {
            if (value) disabled.add(id) else disabled.remove(id)
            onChange(disabled)
        }

        fun setAll(value: Boolean) {
            disabled.clear()
            if (!value) disabled.addAll(items.map { it.id })
            onChange(disabled)
        }
    }

    override fun onDestroyView() {
        testScope.cancel()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
