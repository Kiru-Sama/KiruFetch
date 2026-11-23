package com.kirufetch.app

import android.app.Application
import com.kirufetch.app.data.DownloadStore
import com.kirufetch.app.download.DownloadManager
import com.kirufetch.app.sniff.MediaCandidate
import kotlinx.coroutines.launch

class KiruFetchApp : Application() {

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        // debug 包打开 WebView 远程调试，白屏这类问题可以直接在 chrome://inspect 里看控制台
        if (com.kirufetch.app.BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        DownloadManager.ensureChannel(this)

        appScope.launch {
            val store = DownloadStore.get(this@KiruFetchApp)
            store.refresh()

            val manager = DownloadManager.get(this@KiruFetchApp)
            store.pending().forEach { entity ->
                manager.start(
                    MediaCandidate(
                        id = entity.id,
                        url = entity.url,
                        pageUrl = entity.pageUrl,
                        kind = MediaCandidate.fromStored(entity.kind)
                    ),
                    // 带上用户当初选的清晰度，否则续传会退回 master 的最高码率
                    entity.variantUrl
                )
            }
        }
    }
}
