package com.kirufetch.app.download

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kirufetch.app.sniff.MediaCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 列出 HLS 清晰度供选择。单码率时直接回调 null，按原地址下载。
 */
object QualityPicker {

    fun show(
        activity: AppCompatActivity,
        candidate: MediaCandidate,
        onPick: (url: String?, label: String?) -> Unit
    ) {
        if (candidate.kind != MediaCandidate.Kind.HLS) {
            onPick(null, null)
            return
        }

        val headers = HeaderFactory.build(
            targetUrl = candidate.url,
            pageUrl = candidate.pageUrl,
            allowCookies = false
        )

        activity.lifecycleScope.launch {
            val variants = withContext(Dispatchers.IO) {
                HlsProbe().variants(candidate.url, headers).getOrNull().orEmpty()
            }

            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (variants.size > 1) {
                val labels = variants.map { labelOf(it) }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("选择清晰度")
                    .setItems(labels) { _, which ->
                        onPick(variants[which].url, labels[which])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                onPick(null, null)
            }
        }
    }

    private fun labelOf(v: HlsVariant): String {
        val size = if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else "未知分辨率"
        val mbps = if (v.bandwidth > 0) " · %.1f Mbps".format(v.bandwidth / 1_000_000.0) else ""
        val codec = v.codecs?.takeIf { it.isNotBlank() }?.let { " · ${it.take(20)}" } ?: ""
        return size + mbps + codec
    }
}
