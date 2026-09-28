package com.streamly.settings

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.streamly.BuildConfig
import com.streamly.StreamlyPlugin

class StreamlyMainSettingsFragment(
    private val plugin: StreamlyPlugin,
    private val sharedPref: SharedPreferences
) : DialogFragment() {

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

    private val res: Resources = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Resources.NotFoundException("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Resources.NotFoundException("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_main_settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (layoutId == 0) throw Resources.NotFoundException("Layout fragment_main_settings not found.")
        val view = inflater.inflate(res.getLayout(layoutId), container, false)

        val bgId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (bgId != 0) {
            view.background = res.getDrawable(bgId, null)
        }

        val saveBtn: View = view.findView("saveIcon")
        saveBtn.makeTvCompatible()
        val saveIcon = view.findView<ImageView>("saveIconImg")
        saveIcon.setImageDrawable(getDrawable("save_icon"))
        saveIcon.isFocusable = false
        saveIcon.isClickable = false

        val providersRow: View = view.findView("providersRow")
        val providersIconBg = view.findView<View>("providersIconBg")
        val providersIcon = view.findView<ImageView>("providersIcon")
        val chevronProviders = view.findView<ImageView>("chevronProviders")
        providersRow.background = getDrawable("settings_item_background")
        providersIconBg.background = getDrawable("ic_icon_bg_blue")
        providersIcon.setImageDrawable(getDrawable("ic_providers"))
        chevronProviders.setImageDrawable(getDrawable("ic_chevron"))
        providersRow.nextFocusUpId = saveBtn.id

        val languageRow: View = view.findView("languageRow")
        val languageIconBg = view.findView<View>("languageIconBg")
        val languageIcon = view.findView<ImageView>("languageIcon")
        val chevronLanguage = view.findView<ImageView>("chevronLanguage")
        languageRow.background = getDrawable("settings_item_background")
        languageIconBg.background = getDrawable("ic_icon_bg_green")
        languageIcon.setImageDrawable(getDrawable("ic_language"))
        chevronLanguage.setImageDrawable(getDrawable("ic_chevron"))
        languageRow.nextFocusUpId = providersRow.id

        val stremioRow: View = view.findView("stremioRow")
        val stremioIconBg = view.findView<View>("stremioIconBg")
        val stremioIcon = view.findView<ImageView>("stremioIcon")
        val chevronStremio = view.findView<ImageView>("chevronStremio")
        stremioRow.background = getDrawable("settings_item_background")
        stremioIconBg.background = getDrawable("ic_icon_bg_purple")
        stremioIcon.setImageDrawable(getDrawable("ic_addon"))
        chevronStremio.setImageDrawable(getDrawable("ic_chevron"))
        stremioRow.nextFocusUpId = languageRow.id

        val showSubFragment = { fragmentCreator: (() -> Unit) -> DialogFragment, tag: String ->
            val fm = activity?.supportFragmentManager
            if (fm != null) {
                dismiss()
                val subFragment = fragmentCreator {
                    val mainSettings = StreamlyMainSettingsFragment(plugin, sharedPref)
                    mainSettings.show(fm, "streamly_main_settings")
                }
                subFragment.show(fm, tag)
            }
        }

        providersRow.setOnClickListener {
            showSubFragment({ cb -> StreamlyProvidersFragment(plugin, sharedPref, cb) }, "streamly_providers")
        }

        languageRow.setOnClickListener {
            showSubFragment({ cb -> StreamlyLanguageFragment(plugin, sharedPref, cb) }, "streamly_language")
        }

        stremioRow.setOnClickListener {
            showSubFragment({ cb -> StreamlyStremioFragment(plugin, sharedPref, cb) }, "streamly_stremio")
        }

        saveBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Settings have been saved. Restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    showToast("Settings saved. Restart app to apply changes.")
                }
                .show()
        }

        return view
    }

    /** Full app restart (Cricify-style): uses applicationContext so it
     * survives the fragment dismiss. */
    private fun restartApp() {
        val context = requireContext().applicationContext
        val component = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.component
        if (component != null) {
            context.startActivity(Intent.makeRestartActivityTask(component))
            Runtime.getRuntime().exit(0)
        }
    }
}
