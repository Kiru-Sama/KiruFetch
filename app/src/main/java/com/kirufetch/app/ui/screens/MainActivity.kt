package com.kirufetch.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kirufetch.app.BuildConfig
import com.kirufetch.app.R
import com.kirufetch.app.download.DownloadManager
import com.kirufetch.app.settings.AppPrefs
import com.kirufetch.app.update.ApkUpdater
import com.kirufetch.app.update.UpdateChecker
import com.kirufetch.app.update.UpdateInfo
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingUrl: String? = null
    private var currentTab = 0
    private lateinit var backdrop: com.kirufetch.app.ui.widget.MinimalFieldView

    /** 背景只做"被动"响应：转发触点位置，不拦截任何事件。 */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::backdrop.isInitialized) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    backdrop.updatePointer(ev.x, ev.y, true)

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    backdrop.updatePointer(ev.x, ev.y, false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        // 每次回到前台都检查一次，10 分钟内不重复请求；首次教程没看完先不打扰
        val prefs = AppPrefs(this)
        val now = System.currentTimeMillis()
        if (prefs.onboarded && now - prefs.lastUpdateCheck > 10 * 60 * 1000L) {
            prefs.lastUpdateCheck = now
            maybeCheckUpdate(manual = false)
        }
    }

    /** 设置页点"检查更新"时调用。 */
    fun checkUpdateManually() = maybeCheckUpdate(manual = true)

    private fun maybeCheckUpdate(manual: Boolean) {
        val prefs = AppPrefs(this)
        if (!manual && !prefs.autoCheckUpdate) return
        if (manual) {
            android.widget.Toast.makeText(this, R.string.update_checking, android.widget.Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val info = UpdateChecker.latest()
            if (info == null) {
                if (manual) toast(R.string.update_check_failed)
                return@launch
            }
            if (!UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                if (manual) toast(R.string.update_is_latest)
                return@launch
            }
            if (!manual && prefs.skippedVersion == info.version) return@launch
            showUpdateDialog(info, prefs)
        }
    }

    private fun toast(resId: Int) {
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showUpdateDialog(info: UpdateInfo, prefs: AppPrefs) {
        val notes = cleanNotes(info.notes)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_found_title, info.version))
            .setMessage(notes)
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(info) }
            .setNeutralButton(R.string.update_skip) { _, _ -> prefs.skippedVersion = info.version }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** Release 正文是 markdown，弹窗里不需要星号和标题符号；太长会挤掉按钮，这里截断。 */
    private fun cleanNotes(raw: String): String {
        val text = raw
            .replace("**", "")
            .replace("`", "")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .trim()
        if (text.isBlank()) return getString(R.string.update_no_notes)
        return if (text.length <= 120) text else text.take(120).trimEnd() + "…"
    }

    private fun startUpdate(info: UpdateInfo) {
        if (!ApkUpdater.canInstall(this)) {
            toast(R.string.update_need_permission)
            ApkUpdater.openInstallPermission(this)
            return
        }

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val label = android.widget.TextView(this).apply {
            text = getString(R.string.update_downloading)
            setPadding(pad, pad, pad, (8 * density).toInt())
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(pad, 0, pad, pad)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(box)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val file = ApkUpdater.download(this@MainActivity, info.apkUrl) { percent ->
                runOnUiThread { bar.progress = percent }
            }
            dialog.dismiss()
            if (file == null) {
                toast(R.string.update_download_failed)
                return@launch
            }
            if (!ApkUpdater.install(this@MainActivity, file)) {
                toast(R.string.update_install_failed)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        backdrop = findViewById(R.id.backdrop)

        DownloadManager.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        currentTab = savedInstanceState?.getInt(KEY_TAB) ?: 0

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            show(item.itemId, true)
            true
        }

        if (!AppPrefs(this).onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }
                if (currentTab == R.id.nav_browser) {
                    val browser = supportFragmentManager
                        .findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
                    if (browser != null && browser.handleBack()) return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        handleIntent(intent, savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, null)
    }

    private fun handleIntent(intent: Intent?, savedInstanceState: Bundle?) {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            if (nav.selectedItemId != R.id.nav_downloads) {
                nav.selectedItemId = R.id.nav_downloads
            } else {
                show(R.id.nav_downloads, false)
            }
            return
        }

        val sharedUrl = extractSharedUrl(intent)
        if (!sharedUrl.isNullOrBlank()) {
            openUrlInBrowser(sharedUrl)
            return
        }

        if (savedInstanceState == null) {
            show(R.id.nav_browser, false)
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://[\\S]+").find(text)?.value
    }

    /** 切到浏览器 tab 并让它加载指定 url。 */
    fun openUrlInBrowser(url: String) {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val tag = R.id.nav_browser.toString()

        // 浏览器页已经在前台：直接在当前标签里打开
        val visible = (supportFragmentManager.findFragmentByTag(tag) as? BrowserFragment)
            ?.takeIf { currentTab == R.id.nav_browser && !it.isHidden }
        if (visible != null) {
            visible.loadUrl(url)
            return
        }

        // 否则先记下来，等浏览器页真正显示出来再消费（commit 是异步的，不能立刻调）
        pendingUrl = url
        if (nav.selectedItemId != R.id.nav_browser) {
            nav.selectedItemId = R.id.nav_browser
        }
        show(R.id.nav_browser, false)
    }

    fun consumePendingUrl(): String? {
        val url = pendingUrl
        pendingUrl = null
        return url
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    /**
     * 底部导航用 add/show/hide，不用 replace：
     * replace 会走浏览器页的 onDestroyView，把标签页里的 WebView 全销毁，切回来就白屏重载。
     */
    private fun show(tabId: Int, animated: Boolean) {
        val fm = supportFragmentManager
        if (fm.isStateSaved) return

        val tag = tabId.toString()
        val existing = fm.findFragmentByTag(tag)
        if (currentTab == tabId && existing != null && !existing.isHidden) return
        currentTab = tabId

        // 书签页是盖在容器上的，切底部导航前先收掉，避免压在下载/设置页上面
        if (fm.backStackEntryCount > 0) {
            fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        val tx = fm.beginTransaction()
        if (animated) tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)

        fm.fragments.forEach { fragment ->
            if (fragment.tag != tag && !fragment.isHidden) tx.hide(fragment)
        }

        val target = existing ?: create(tabId).also { tx.add(R.id.fragment_container, it, tag) }
        tx.show(target)
        tx.commitAllowingStateLoss()
    }

    private fun create(tabId: Int): Fragment = when (tabId) {
        R.id.nav_downloads -> DownloadsFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> BrowserFragment()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        private const val KEY_TAB = "current_tab"
    }
}
