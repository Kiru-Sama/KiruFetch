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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kirufetch.app.R
import com.kirufetch.app.download.DownloadManager

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
