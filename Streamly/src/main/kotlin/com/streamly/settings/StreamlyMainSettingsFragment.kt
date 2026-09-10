package com.streamly.settings

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import androidx.core.content.edit
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

        val saveIcon = view.findView<ImageView>("saveIcon")
        saveIcon.setImageDrawable(getDrawable("save_icon"))
        saveIcon.makeTvCompatible()

        val providersRow: View = view.findView("providersRow")
        val providersIcon = view.findView<ImageView>("providersIcon")
        providersRow.background = getDrawable("settings_item_background")
        providersIcon.setImageDrawable(getDrawable("settings_icon"))

        val hideMetaRow: View = view.findView("hideMetaRow")
        hideMetaRow.background = getDrawable("settings_item_background")

        val showMetaSwitch = view.findView<Switch>("hideMetaSwitch")
        showMetaSwitch.isChecked = sharedPref.getBoolean("show_episode_meta", false)
        showMetaSwitch.makeTvCompatible()

        hideMetaRow.setOnClickListener { showMetaSwitch.isChecked = !showMetaSwitch.isChecked }
        showMetaSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit { putBoolean("show_episode_meta", isChecked) }
        }

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

        saveIcon.setOnClickListener {
            showToast("Settings saved")
            dismiss()
        }

        return view
    }
}
