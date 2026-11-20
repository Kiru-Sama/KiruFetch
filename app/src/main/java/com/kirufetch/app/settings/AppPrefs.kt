package com.kirufetch.app.settings

import android.content.Context
import com.kirufetch.app.download.HeaderFactory

class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("kirufetch", Context.MODE_PRIVATE)

    var threads: Int
        get() = sp.getInt(KEY_THREADS, 8).coerceIn(1, 16)
        set(value) = sp.edit().putInt(KEY_THREADS, value.coerceIn(1, 16)).apply()

    var userAgent: String
        get() = sp.getString(KEY_UA, HeaderFactory.DEFAULT_UA) ?: HeaderFactory.DEFAULT_UA
        set(value) = sp.edit().putString(KEY_UA, value).apply()

    var allowCookies: Boolean
        get() = sp.getBoolean(KEY_COOKIES, false)
        set(value) = sp.edit().putBoolean(KEY_COOKIES, value).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP, value).apply()

    var clipboardWatch: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    var searchEngineIndex: Int
        get() = sp.getInt(KEY_SEARCH_ENGINE, 0)
        set(value) = sp.edit().putInt(KEY_SEARCH_ENGINE, value).apply()

    var autoSniff: Boolean
        get() = sp.getBoolean(KEY_AUTO_SNIFF, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SNIFF, value).apply()

    /** 用户选的下载目录（SAF tree uri），为空表示用应用专属目录。 */
    var downloadTreeUri: String?
        get() = sp.getString(KEY_TREE_URI, null)
        set(value) = sp.edit().putString(KEY_TREE_URI, value).apply()

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val KEY_THREADS = "threads"
        private const val KEY_UA = "user_agent"
        private const val KEY_COOKIES = "allow_cookies"
        private const val KEY_DESKTOP = "desktop_mode"
        private const val KEY_AUTO_SNIFF = "auto_sniff"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CLIPBOARD = "clipboard_watch"
        private const val KEY_TREE_URI = "download_tree_uri"
    }
}
