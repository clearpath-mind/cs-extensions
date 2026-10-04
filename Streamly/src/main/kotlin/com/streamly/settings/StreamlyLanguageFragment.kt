package com.streamly.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.DialogFragment
import com.streamly.BuildConfig
import com.streamly.StreamlyPlugin
import androidx.core.content.edit

class StreamlyLanguageFragment(
    plugin: StreamlyPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
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

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

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

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    // Language Display List
    private val languages = listOf(
        "English" to "en-US",
        "Arabic (العربية)" to "ar-SA"
    )


    private lateinit var adapter: LanguageAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {

        val root = getLayout("fragment_language_select", inflater, container)
        val drawableId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (drawableId != 0) {
            root.background = res.getDrawable(drawableId, null)
        }

        val recycler: RecyclerView = root.findView("languageRecycler")
        recycler.makeTvCompatible()

        recycler.layoutManager = LinearLayoutManager(requireContext())

        val savedCode = sharedPref.getString("tmdb_language_code", "en-US") ?: "en-US"

        adapter = LanguageAdapter(
            languages,
            savedCode
        ) { code ->
            sharedPref.edit { putString("tmdb_language_code", code) }
            Toast.makeText(requireContext(), "Language set to $code", Toast.LENGTH_SHORT).show()
            dismiss()
        }


        recycler.adapter = adapter

        return root
    }


    // ---------------------------------------------------------------------- ADAPTER ------------------ //

    inner class LanguageAdapter(
        private val originalList: List<Pair<String, String>>,
        private val selectedCode: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findView("radio_language")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = getLayout("item_language", LayoutInflater.from(parent.context), parent)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, code) = originalList[position]

            holder.radio.text = name
            holder.radio.isChecked = code == selectedCode

            holder.radio.setOnClickListener {
                onClick(code)
            }
        }

        override fun getItemCount() = originalList.size
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
