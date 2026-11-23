package com.kirufetch.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.kirufetch.app.R
import com.kirufetch.app.download.StorageTarget
import com.kirufetch.app.settings.AppPrefs

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private companion object {
        const val REQ_STORAGE = 1001
    }

    private lateinit var prefs: AppPrefs
    private lateinit var storagePath: TextView
    private lateinit var storageReset: TextView

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        // 没拿到持久化授权的话重启就失效了，这里不阻断，只是下次会回退
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        AppPrefs(requireContext()).downloadTreeUri = uri.toString()
        renderStorage()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPrefs(requireContext())

        val threadsValue = view.findViewById<TextView>(R.id.setting_threads_value)
        val seek = view.findViewById<SeekBar>(R.id.setting_threads_seek)
        val ua = view.findViewById<EditText>(R.id.setting_ua)
        val cookies = view.findViewById<Switch>(R.id.setting_cookies)
        val autoSniff = view.findViewById<Switch>(R.id.setting_auto_sniff)
        val clipboard = view.findViewById<Switch>(R.id.setting_clipboard)
        val gallery = view.findViewById<Switch>(R.id.setting_gallery)
        val autoUpdate = view.findViewById<Switch>(R.id.setting_auto_update)
        storagePath = view.findViewById(R.id.setting_storage_path)
        storageReset = view.findViewById(R.id.setting_storage_reset)

        fun renderThreads() {
            threadsValue.text = getString(R.string.setting_threads_value, prefs.threads)
            seek.progress = (prefs.threads - 1).coerceIn(0, 15)
        }

        renderThreads()
        ua.setText(prefs.userAgent)
        cookies.isChecked = prefs.allowCookies
        autoSniff.isChecked = prefs.autoSniff
        clipboard.isChecked = prefs.clipboardWatch
        gallery.isChecked = prefs.saveToGallery
        autoUpdate.isChecked = prefs.autoCheckUpdate
        view.findViewById<android.widget.TextView>(R.id.setting_version_value).text =
            getString(R.string.setting_update, com.kirufetch.app.BuildConfig.VERSION_NAME)

        renderStorage()

        view.findViewById<View>(R.id.setting_storage_row).setOnClickListener {
            pickFolder.launch(null)
        }
        storageReset.setOnClickListener {
            prefs.downloadTreeUri = null
            renderStorage()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.threads = progress + 1
                    renderThreads()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        ua.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && ua.text.isNotBlank()) prefs.userAgent = ua.text.toString()
        }

        cookies.setOnCheckedChangeListener { _, checked -> prefs.allowCookies = checked }
        autoSniff.setOnCheckedChangeListener { _, checked -> prefs.autoSniff = checked }
        clipboard.setOnCheckedChangeListener { _, checked -> prefs.clipboardWatch = checked }
        autoUpdate.setOnCheckedChangeListener { _, checked -> prefs.autoCheckUpdate = checked }
        view.findViewById<View>(R.id.setting_check_row).setOnClickListener {
            (activity as? MainActivity)?.checkUpdateManually()
        }
        gallery.setOnCheckedChangeListener { _, checked ->
            if (checked && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // Android 8/9 写公共目录要权限；没给就先不打开
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE
                )
                gallery.isChecked = false
                prefs.saveToGallery = false
            } else {
                prefs.saveToGallery = checked
            }
        }
    }

    private fun renderStorage() {
        if (!::storagePath.isInitialized || !isAdded) return

        val tree = prefs.downloadTreeUri
        if (tree.isNullOrBlank()) {
            val dir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: requireContext().filesDir
            storagePath.text = getString(R.string.storage_app_default) + "\n" + dir.absolutePath
            storageReset.visibility = View.GONE
        } else {
            val name = runCatching {
                StorageTarget.displayName(requireContext(), Uri.parse(tree))
            }.getOrNull() ?: tree
            storagePath.text = name
            storageReset.visibility = View.VISIBLE
        }
    }
}
