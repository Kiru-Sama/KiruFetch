package com.kirufetch.app.ui.screens

import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.kirufetch.app.R
import com.kirufetch.app.download.StorageTarget
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        videoView = findViewById(R.id.video_view)
        val blackout = findViewById<android.view.View>(R.id.player_blackout)
        videoView.keepScreenOn = true

        findViewById<android.widget.ImageButton>(R.id.player_back).setOnClickListener { finish() }

        // 首帧真正渲染出来之前，Surface 上是一片未初始化内容（多数机器是绿屏）。
        // 用黑色遮罩盖住，等 RENDERING_START 再撤；3 秒兜底，避免某些机型不回调导致一直黑。
        videoView.setOnInfoListener { _, what, _ ->
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                blackout.visibility = android.view.View.GONE
            }
            false
        }
        // 正常机器上 RENDERING_START 在几百毫秒内就到；10 秒只是兜底，防止个别机型不回调时一直黑屏
        videoView.postDelayed({ blackout.visibility = android.view.View.GONE }, 10000)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank() || !StorageTarget.exists(this, path)) {
            finish()
            return
        }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)

        val uri = if (StorageTarget.isDocumentPath(path)) {
            // 用户把文件存到了自己选的目录，走 content:// 直接播
            android.net.Uri.parse(path)
        } else {
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", File(path)
                )
            } catch (t: Throwable) {
                android.widget.Toast.makeText(
                    this, R.string.play_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }
        }
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { it.isLooping = false }
        videoView.setOnErrorListener { _, _, _ ->
            android.widget.Toast.makeText(
                this, R.string.play_failed, android.widget.Toast.LENGTH_SHORT
            ).show()
            finish()
            true
        }
        videoView.start()
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
    }

    override fun onDestroy() {
        if (::videoView.isInitialized) videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "video_path"
    }
}
