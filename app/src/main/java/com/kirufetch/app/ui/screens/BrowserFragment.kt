package com.kirufetch.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.kirufetch.app.R
import com.kirufetch.app.data.BrowserStore
import com.kirufetch.app.data.SearchEngine
import com.kirufetch.app.download.DownloadManager
import com.kirufetch.app.download.QualityPicker
import com.kirufetch.app.settings.AppPrefs
import com.kirufetch.app.sniff.MediaBridge
import com.kirufetch.app.sniff.MediaCandidate
import com.kirufetch.app.sniff.MediaDetector
import com.kirufetch.app.sniff.MediaSniffScript
import com.kirufetch.app.sniff.UrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BrowserFragment : Fragment(R.layout.fragment_browser) {

    /** 一个标签：独立 WebView + 独立的嗅探结果与请求头缓存。 */
    private inner class Tab(val key: String) {
        lateinit var webView: WebView
        var title: String = ""
        var lastUrl: String = ""
        var tabRef: TabLayout.Tab? = null
        var labelView: TextView? = null
        var posterUrl: String? = null
        val candidates = LinkedHashMap<String, MediaCandidate>()
        val sniffedHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

        val displayTitle: String
            get() = title.ifBlank {
                lastUrl.takeIf { it.isNotBlank() }?.let { runCatching { java.net.URL(it).host }.getOrNull() }
                    ?: getString(R.string.tab_untitled)
            }
    }

    private lateinit var webContainer: FrameLayout
    private lateinit var input: EditText
    private lateinit var adapter: MediaCandidateAdapter
    private lateinit var prefs: AppPrefs
    private lateinit var browserStore: BrowserStore
    private lateinit var tabStrip: TabLayout
    private lateinit var foundText: TextView
    private lateinit var candidateList: RecyclerView

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null
    private var listExpanded = false
    private var lastClip: String? = null

    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        webContainer = view.findViewById(R.id.web_container)
        input = view.findViewById(R.id.url_input)
        tabStrip = view.findViewById(R.id.tab_strip)
        foundText = view.findViewById(R.id.found_text)
        candidateList = view.findViewById(R.id.candidate_list)
        prefs = AppPrefs(requireContext())
        browserStore = BrowserStore.get(requireContext())

        val go = view.findViewById<TextView>(R.id.go_button)
        val back = view.findViewById<ImageButton>(R.id.btn_back)
        val forward = view.findViewById<ImageButton>(R.id.btn_forward)
        val reload = view.findViewById<ImageButton>(R.id.btn_reload)
        val desktop = view.findViewById<ImageButton>(R.id.btn_desktop)
        val more = view.findViewById<ImageButton>(R.id.btn_more)
        val expand = view.findViewById<ImageButton>(R.id.btn_expand)
        val newTab = view.findViewById<ImageButton>(R.id.btn_new_tab)

        adapter = MediaCandidateAdapter { candidate ->
            val host = activity as? androidx.appcompat.app.AppCompatActivity
            if (host != null) {
                QualityPicker.show(host, candidate) { variantUrl, variantLabel ->
                    DownloadManager.get(requireContext())
                        .enqueue(candidate, variantUrl, variantLabel)
                    Toast.makeText(requireContext(), R.string.queued, Toast.LENGTH_SHORT).show()
                }
            }
        }

        candidateList.layoutManager = LinearLayoutManager(requireContext())
        candidateList.adapter = adapter
        candidateList.visibility = View.GONE
        expand.rotation = 180f

        tabStrip.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                (tab.tag as? Tab)?.let { selectTab(it) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        go.setOnClickListener { submitInput() }
        // 键盘上的"前往"要能直接提交，不然只能点按钮
        input.setOnEditorActionListener { _, actionId, event ->
            val imeGo = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN
            if (imeGo || enter) {
                submitInput()
                true
            } else false
        }
        back.setOnClickListener { val t = active ?: return@setOnClickListener; if (t.webView.canGoBack()) t.webView.goBack() }
        forward.setOnClickListener { val t = active ?: return@setOnClickListener; if (t.webView.canGoForward()) t.webView.goForward() }
        reload.setOnClickListener { active?.webView?.reload() }
        desktop.setOnClickListener {
            prefs.desktopMode = !prefs.desktopMode
            applyUserAgent()
            active?.webView?.reload()
        }
        more.setOnClickListener { showMoreMenu() }
        newTab.setOnClickListener {
            selectTab(newTab())
            input.requestFocus()
        }

        fun toggleList() {
            listExpanded = !listExpanded
            candidateList.visibility = if (listExpanded) View.VISIBLE else View.GONE
            expand.rotation = if (listExpanded) 0f else 180f
        }
        expand.setOnClickListener { toggleList() }
        view.findViewById<View>(R.id.found_panel).setOnClickListener { toggleList() }

        val pending = (activity as? MainActivity)?.consumePendingUrl()
        val first = newTab()
        selectTab(first)
        if (!pending.isNullOrBlank()) loadUrl(pending)
    }

    // ---- 标签管理 ----

    private fun newTab(): Tab {
        val tab = Tab(UUID.randomUUID().toString())
        tab.webView = createWebView(tab)
        webContainer.addView(tab.webView)
        tabs += tab

        val ref = tabStrip.newTab()
        ref.tag = tab
        ref.customView = buildTabView(tab)
        tabStrip.addTab(ref, false)
        tab.tabRef = ref
        return tab
    }

    private fun buildTabView(tab: Tab): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (2 * density).toInt(), 0)
        }

        val label = TextView(ctx).apply {
            text = tab.displayTitle
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (130 * density).toInt()
            setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            textSize = 13f
        }
        tab.labelView = label

        val close = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            background = null
            setColorFilter(ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams((30 * density).toInt(), (30 * density).toInt())
            contentDescription = getString(R.string.close_tab)
            setOnClickListener { closeTab(tab) }
        }

        row.addView(label)
        row.addView(close)
        return row
    }

    private fun selectTab(tab: Tab) {
        if (active === tab) {
            renderActive()
            return
        }
        active = tab
        // WebView 用 GONE/VISIBLE 切换在部分机型上会丢掉渲染表面（切回来一片空白），
        // 这里改成全部保持可见、靠 z 序切换，切页后页面不会重绘失败。
        tab.webView.bringToFront()
        input.setText(tab.lastUrl)

        val ref = tab.tabRef
        val selectedRef = tabStrip.selectedTabPosition
            .takeIf { it >= 0 }
            ?.let { tabStrip.getTabAt(it) }
        if (ref != null && selectedRef !== ref) {
            tabStrip.selectTab(ref)
        }
        renderActive()
        updateTabLabel(tab)
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return

        tabs.removeAt(index)
        tab.tabRef?.let { tabStrip.removeTab(it) }
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        runCatching {
            tab.webView.stopLoading()
            tab.webView.removeAllViews()
            tab.webView.destroy()
        }

        if (active === tab) active = null

        if (tabs.isEmpty()) {
            selectTab(newTab())
        } else if (active == null) {
            selectTab(tabs.getOrNull(index) ?: tabs.last())
        }
    }

    private fun updateTabLabel(tab: Tab) {
        tab.labelView?.text = tab.displayTitle
    }

    private fun renderActive() {
        val tab = active ?: return
        adapter.submit(tab.candidates.values.toList())
        foundText.text = if (tab.candidates.isEmpty()) {
            getString(R.string.found_videos)
        } else {
            getString(R.string.found_count, tab.candidates.size)
        }
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(tab: Tab): WebView {
        val wv = WebView(requireContext())
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 透明背景：空白标签页时让被动背景透出来，而不是铺一块白
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // OAuth 登录（X/Google）依赖弹窗和第三方 cookie：
            // 关掉多窗口时 window.open 会静默失败，登录流程停在白页
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            userAgentString = currentUserAgent()
        }

        wv.addJavascriptInterface(
            MediaBridge(
                onUrls = { urls -> urls.forEach { offerUrl(tab, it, null, null) } },
                onPoster = { poster ->
                    if (tab.posterUrl != poster) {
                        tab.posterUrl = poster
                        // 已经抓到的候选没有封面，补上再刷新
                        var changed = false
                        tab.candidates.replaceAll { _, c ->
                            if (c.posterUrl == null) {
                                changed = true
                                c.copy(posterUrl = poster)
                            } else c
                        }
                        if (changed) view?.post { if (isAdded && active === tab) renderActive() }
                    }
                }
            ),
            "KiruFetchBridge"
        )

        // 第三方 cookie 默认是关的，Google/X 的登录往返依赖它
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        // window.open 出来的登录弹窗，接到一个新标签里，登录完再回到原页
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: android.webkit.WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport ?: return false
                val popup = newTab()
                transport.webView = popup.webView
                resultMsg.sendToTarget()
                selectTab(popup)
                return true
            }

            // 白屏排查用：debug 包把网页控制台和加载错误打到 logcat
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                if (com.kirufetch.app.BuildConfig.DEBUG && msg != null) {
                    android.util.Log.d("WVConsole", "${msg.messageLevel()} ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                }
                return false
            }
        }

        wv.webViewClient = object : WebViewClient() {
            /**
             * 非 http(s) 的 scheme（baiduapp://、intent://、market:// …）WebView 自己打不开，
             * 会甩一个 ERR_UNKNOWN_URL_SCHEME 错误页。这里交给系统去唤起对应 App。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val r = request ?: return false
                val scheme = r.url.scheme?.lowercase()
                if (scheme.isNullOrBlank() || scheme == "http" || scheme == "https" ||
                    scheme == "about" || scheme == "file" || scheme == "javascript" ||
                    scheme == "data" || scheme == "blob"
                ) {
                    return false
                }
                return runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.url))
                    true
                }.getOrDefault(false)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val r = request ?: return null

                // 直接打开 mpd/m3u8 这类非 HTML 资源时 onPageStarted 不一定回调，
                // 用主框架请求把当前页地址补上，否则 Referer/pageUrl 会是上一个页面
                if (r.isForMainFrame) tab.lastUrl = r.url.toString()

                if (!prefs.autoSniff) return null

                val url = r.url.toString()
                if (MediaDetector.isMediaUrl(url, null)) {
                    val normalized = UrlNormalizer.normalize(url)
                    tab.sniffedHeaders[normalized] = r.requestHeaders.orEmpty()
                    offerUrl(tab, url, null, r.requestHeaders.orEmpty())
                }
                return null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    android.util.Log.w(
                        "WVConsole",
                        "main-frame error ${error?.errorCode} ${error?.description} ${request.url}"
                    )
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url.isNullOrBlank()) return
                tab.lastUrl = url
                if (active === tab) input.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank()) return
                tab.lastUrl = url
                tab.title = runCatching { view?.title }.getOrNull().orEmpty()
                updateTabLabel(tab)

                ioScope.launch {
                    browserStore.addHistory(tab.title, url)
                }

                if (prefs.autoSniff) {
                    view?.evaluateJavascript(MediaSniffScript.SCRIPT, null)
                }
            }
        }

        return wv
    }

    private fun currentUserAgent(): String =
        if (prefs.desktopMode) AppPrefs.DESKTOP_UA else prefs.userAgent

    private fun applyUserAgent() {
        val ua = currentUserAgent()
        tabs.forEach { it.webView.settings.userAgentString = ua }
    }

    // ---- 嗅探 ----

    private fun offerUrl(tab: Tab, url: String, mime: String?, headers: Map<String, String>?) {
        val keyUrl = UrlNormalizer.normalize(url)
        if (!UrlNormalizer.isCandidate(keyUrl)) return

        val kind = MediaDetector.kindOf(keyUrl, mime) ?: return
        val downloadUrl = UrlNormalizer.normalizeForDownload(url)
        val id = UrlNormalizer.sha256(keyUrl)
        if (headers != null) tab.sniffedHeaders[keyUrl] = headers
        val merged = headers ?: tab.sniffedHeaders[keyUrl].orEmpty()
        val pageUrl = tab.lastUrl.takeIf { it.isNotBlank() }

        val host = view ?: return
        host.post {
            if (!isAdded) return@post
            val before = tab.candidates.size
            tab.candidates.putIfAbsent(
                id,
                MediaCandidate(
                    id = id,
                    url = downloadUrl,
                    pageUrl = pageUrl,
                    headers = merged,
                    kind = kind,
                    mimeType = mime,
                    posterUrl = tab.posterUrl
                )
            )
            if (tab.candidates.size == before) return@post
            if (active === tab) renderActive()
        }
    }

    // ---- 生命周期 ----

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized || !::input.isInitialized) return
        if (prefs.clipboardWatch) checkClipboard()
    }

    private fun checkClipboard() {
        val cm = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            ?: return

        if (!text.startsWith("http") || text == lastClip) return
        lastClip = text

        val tab = active ?: return
        if (text == tab.lastUrl) return

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clipboard_found)
            .setMessage(text.take(120))
            .setPositiveButton(R.string.open_link) { _, _ -> tab.webView.loadUrl(text) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 返回键：先走网页历史，再关标签，最后交还给 Activity。 */
    fun handleBack(): Boolean {
        val tab = active ?: return false
        return if (tab.webView.canGoBack()) {
            tab.webView.goBack()
            true
        } else if (tabs.size > 1) {
            closeTab(tab)
            true
        } else false
    }

    fun loadUrl(url: String) {
        if (!::input.isInitialized) return
        val tab = active
        if (tab == null) {
            selectTab(newTab())
            active?.webView?.loadUrl(url)
        } else {
            tab.webView.loadUrl(url)
        }
        input.setText(url)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (activity as? MainActivity)?.consumePendingUrl()?.let { loadUrl(it) }
        }
    }

    override fun onDestroyView() {
        ioScope.cancel()
        tabs.forEach { tab ->
            runCatching {
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.removeAllViews()
                tab.webView.destroy()
            }
        }
        tabs.clear()
        active = null
        super.onDestroyView()
    }

    // ---- 地址栏与菜单 ----

    private fun submitInput() {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return
        val tab = active ?: return

        val looksLikeUrl = raw.startsWith("http://") || raw.startsWith("https://") ||
                (raw.contains(".") && !raw.contains(" "))

        if (looksLikeUrl) {
            val url = if (raw.contains("://")) raw else "https://$raw"
            tab.webView.loadUrl(url)
        } else {
            val engines = SearchEngine.values()
            val engine = engines.getOrElse(prefs.searchEngineIndex) { SearchEngine.GOOGLE }
            tab.webView.loadUrl(engine.buildUrl(raw))
        }
    }

    private fun showMoreMenu() {
        val tab = active ?: return
        val currentUrl = tab.lastUrl

        viewLifecycleOwner.lifecycleScope.launch {
            val bookmarked = currentUrl.isNotBlank() &&
                    withContext(Dispatchers.IO) { browserStore.isBookmarked(currentUrl) }
            if (!isAdded) return@launch
            showMoreMenuDialog(tab, currentUrl, bookmarked)
        }
    }

    private fun showMoreMenuDialog(tab: Tab, currentUrl: String, bookmarked: Boolean) {
        val items = mutableListOf<String>()
        items += if (bookmarked) getString(R.string.remove_bookmark) else getString(R.string.add_bookmark)
        items += if (prefs.desktopMode) getString(R.string.desktop_off) else getString(R.string.desktop_on)
        items += getString(R.string.bookmark_list)
        items += getString(R.string.search_engine)
        items += getString(R.string.copy_url)
        items += getString(R.string.clear_list)
        items += getString(R.string.reload)
        items += getString(R.string.share_link)
        items += getString(R.string.open_external)
        items += getString(R.string.forward)

        AlertDialog.Builder(requireContext())
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> toggleBookmark(tab, currentUrl, bookmarked)
                    1 -> {
                        prefs.desktopMode = !prefs.desktopMode
                        applyUserAgent()
                        tab.webView.reload()
                    }
                    2 -> openBookmarkList()
                    3 -> chooseSearchEngine()
                    4 -> copyCurrentUrl(currentUrl)
                    5 -> clearCandidates(tab)
                    6 -> tab.webView.reload()
                    7 -> shareUrl(currentUrl)
                    8 -> openExternal(currentUrl)
                    9 -> if (tab.webView.canGoForward()) tab.webView.goForward()
                }
            }
            .show()
    }

    private fun toggleBookmark(tab: Tab, url: String, currentlyBookmarked: Boolean) {
        if (url.isBlank()) return
        val title = tab.title

        ioScope.launch {
            if (currentlyBookmarked) {
                browserStore.bookmarks.value.firstOrNull { it.url == url }?.let {
                    browserStore.removeBookmark(it.id)
                }
            } else {
                browserStore.addBookmark(title, url)
            }
        }
    }

    /**
     * 书签页用 add 而不是 replace：replace 会走 onDestroyView，把标签里的 WebView 全销毁。
     * 书签页自己是全屏不透明背景，直接盖在上面即可。
     */
    private fun openBookmarkList() {
        parentFragmentManager.beginTransaction()
            .add(R.id.fragment_container, BookmarksFragment())
            .addToBackStack("bookmarks")
            .commit()
    }

    private fun chooseSearchEngine() {
        val engines = SearchEngine.values()
        val labels = engines.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(labels, prefs.searchEngineIndex) { dialog, which ->
                prefs.searchEngineIndex = which
                dialog.dismiss()
            }
            .show()
    }

    private fun shareUrl(url: String) {
        if (url.isBlank()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_link)))
    }

    private fun openExternal(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCurrentUrl(url: String) {
        if (url.isBlank()) return
        val cm = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        Toast.makeText(requireContext(), R.string.copy_link, Toast.LENGTH_SHORT).show()
    }

    private fun clearCandidates(tab: Tab) {
        tab.candidates.clear()
        tab.sniffedHeaders.clear()
        if (active === tab) renderActive()
    }
}
