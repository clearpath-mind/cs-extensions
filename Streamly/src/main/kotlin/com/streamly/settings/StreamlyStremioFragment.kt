package com.streamly.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.app
import com.streamly.BuildConfig
import com.streamly.StreamlyPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StreamlyStremioFragment(
    private val plugin: StreamlyPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private lateinit var container: LinearLayout
    private lateinit var etName: EditText
    private lateinit var etUrl: EditText
    private var selectedType: StreamlyStremioAddonType = StreamlyStremioAddonType.HTTPS
    private val typeButtons = mutableListOf<Button>()

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = getLayout("fragment_stremio", inflater, container)
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

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findView("list_container")
        container.makeTvCompatible()

        val btnSave = view.findView<ImageButton>("btn_save")
        btnSave.setImageDrawable(getDrawable("save_icon"))
        btnSave.makeTvCompatible()
        btnSave.setOnClickListener { dismiss() }

        etName = view.findView("et_addon_name")
        etUrl = view.findView("et_addon_url")
        etName.background = getDrawable("input_text_selector")
        etUrl.background = getDrawable("input_text_selector")

        val btnHttps = view.findView<Button>("btn_type_https")
        val btnTorrent = view.findView<Button>("btn_type_torrent")
        val btnSubs = view.findView<Button>("btn_type_subtitle")
        val btnDebrid = view.findView<Button>("btn_type_debrid")
        typeButtons.addAll(listOf(btnHttps, btnTorrent, btnSubs, btnDebrid))
        btnHttps.setOnClickListener { selectType(StreamlyStremioAddonType.HTTPS) }
        btnTorrent.setOnClickListener { selectType(StreamlyStremioAddonType.TORRENT) }
        btnSubs.setOnClickListener { selectType(StreamlyStremioAddonType.SUBTITLE) }
        btnDebrid.setOnClickListener { selectType(StreamlyStremioAddonType.DEBRID) }
        selectType(selectedType)

        val btnAdd = view.findView<Button>("btn_add_addon")
        btnAdd.background = getDrawable("btn_green_selector")
        btnAdd.setOnClickListener { addAddon() }

        renderAddons()
    }

    private fun selectType(type: StreamlyStremioAddonType) {
        selectedType = type
        val selectedBg = getDrawable("btn_blue_selector")
        val unselectedBg = getDrawable("settings_item_background")
        val selectedText = android.graphics.Color.WHITE
        val unselectedText = android.graphics.Color.parseColor("#888888")
        typeButtons.forEach { btn ->
            val isSelected = when (btn.id) {
                res.getIdentifier("btn_type_https", "id", BuildConfig.LIBRARY_PACKAGE_NAME) -> type == StreamlyStremioAddonType.HTTPS
                res.getIdentifier("btn_type_torrent", "id", BuildConfig.LIBRARY_PACKAGE_NAME) -> type == StreamlyStremioAddonType.TORRENT
                res.getIdentifier("btn_type_subtitle", "id", BuildConfig.LIBRARY_PACKAGE_NAME) -> type == StreamlyStremioAddonType.SUBTITLE
                else -> type == StreamlyStremioAddonType.DEBRID
            }
            btn.background = if (isSelected) selectedBg else unselectedBg
            btn.setTextColor(if (isSelected) selectedText else unselectedText)
        }
    }

    private fun renderAddons() {
        container.removeAllViews()
        val addons = StreamlyStremioSettings.getStremioAddons(sharedPref)
        addons.forEach { addon ->
            val item = getLayout("item_stremio_addon", layoutInflater, container)
            item.findView<TextView>("tv_addon_name").text = addon.name
            item.findView<TextView>("tv_addon_url").text = addon.url
            item.findView<TextView>("tv_addon_type").text = addon.type.name
            item.background = getDrawable("settings_item_background")
            val btnDelete = item.findView<ImageButton>("btn_delete_addon")
            btnDelete.setImageDrawable(getDrawable("delete_icon"))
            btnDelete.setOnClickListener {
                val updated = StreamlyStremioSettings.getStremioAddons(sharedPref)
                    .filterNot { it.id == addon.id }
                StreamlyStremioSettings.saveStremioAddons(sharedPref, updated)
                showToast("Addon removed")
                renderAddons()
            }
            container.addView(item)
        }
        if (addons.isEmpty()) {
            val hint = TextView(requireContext()).apply {
                text = "No addons yet — paste one below."
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 13f
            }
            container.addView(hint)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addAddon() {
        val name = etName.text.toString().trim()
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            showToast("Enter a manifest URL")
            return
        }
        if (url.startsWith("stremio://")) url = "https://" + url.removePrefix("stremio://")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val base = url.trimEnd('/').removeSuffix("/manifest.json")
        // Some instances (e.g. akwam.driviumapp.com) serve a broken TLS cert:
        // try https first, then fall back to http and store whichever works.
        val bases = mutableListOf(base)
        if (base.startsWith("https://")) bases.add("http://" + base.removePrefix("https://"))
        CoroutineScope(Dispatchers.IO).launch {
            var manifest: StreamlyStremioManifest? = null
            var workingBase: String? = null
            for (candidate in bases) {
                manifest = runCatching {
                    app.get("$candidate/manifest.json", timeout = 15000)
                        .parsedSafe<StreamlyStremioManifest>()
                        .takeIf { !it?.id.isNullOrBlank() }
                }.getOrNull()
                if (manifest != null) {
                    workingBase = candidate
                    break
                }
            }
            view?.post {
                if (!isAdded) return@post
                val addonBase = workingBase
                if (manifest == null || addonBase == null) {
                    showToast("Invalid addon (no manifest.json)")
                    return@post
                }
                val addonName = name.ifBlank { manifest.name ?: addonBase }
                val current = StreamlyStremioSettings.getStremioAddons(sharedPref).toMutableList()
                current.removeAll { it.url.trimEnd('/') == addonBase }
                current.add(
                    StreamlyStremioAddon(
                        id = System.currentTimeMillis(),
                        name = addonName,
                        url = addonBase,
                        type = selectedType
                    )
                )
                StreamlyStremioSettings.saveStremioAddons(sharedPref, current)
                etName.text.clear()
                etUrl.text.clear()
                if (addonBase.startsWith("http://")) {
                    showToast("Addon added via http (https cert failed)")
                } else {
                    showToast("Addon added: $addonName")
                }
                renderAddons()
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
