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

    /** 是否看过首次教程。 */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /** 上次检查更新的时间，避免每次切回前台都发请求。 */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE, value).apply()

    /** 启动时是否检查 GitHub Release 更新。默认开。 */
    var autoCheckUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** 用户点了"跳过此版本"的版本号。 */
    var skippedVersion: String?
        get() = sp.getString(KEY_SKIPPED_VERSION, null)
        set(value) = sp.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    /** 下载完成后是否写入系统媒体库（相册）。默认开。 */
    var saveToGallery: Boolean
        get() = sp.getBoolean(KEY_GALLERY, true)
        set(value) = sp.edit().putBoolean(KEY_GALLERY, value).apply()

    /** 用户选的下载目录（SAF tree uri），为空表示保存到相册。 */
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
        private const val KEY_GALLERY = "save_to_gallery"
        private const val KEY_AUTO_UPDATE = "auto_check_update"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_LAST_UPDATE = "last_update_check"
    }
}
