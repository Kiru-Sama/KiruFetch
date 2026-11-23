package com.kirufetch.app.ui.screens

import android.media.AudioManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.kirufetch.app.R
import com.kirufetch.app.download.StorageTarget
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var controller: MediaController
    private lateinit var hud: TextView
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private var downY = 0f
    private var dragging = false
    private var dragSide = 0 // 1=亮度 2=音量
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var maxVolume = 1
    private val hudRunnable = Runnable { hud.visibility = View.GONE }

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

        controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)

        hud = findViewById(R.id.player_hud)
        maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        setupGestures(findViewById(R.id.player_gesture))

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

    /** 单击显隐控制条、双击左右快退/快进、上下滑调亮度/音量。 */
    private fun setupGestures(layer: View) {
        val density = resources.displayMetrics.density
        val threshold = 24 * density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controller.isShowing) controller.hide() else controller.show(3000)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val stepMs = 10_000
                if (e.x < videoView.width / 2f) {
                    videoView.seekTo((videoView.currentPosition - stepMs).coerceAtLeast(0))
                    flashHud(getString(R.string.hud_backward, 10))
                } else {
                    val target = (videoView.currentPosition + stepMs)
                        .coerceAtMost(videoView.duration.coerceAtLeast(0))
                    videoView.seekTo(target)
                    flashHud(getString(R.string.hud_forward, 10))
                }
                return true
            }
        })

        layer.setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    dragging = false
                    dragSide = 0
                    startBrightness = window.attributes.screenBrightness
                    if (startBrightness < 0f) startBrightness = 0.5f
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - downY
                    if (!dragging && kotlin.math.abs(dy) > threshold) {
                        dragging = true
                        dragSide = if (ev.x < v.width / 2f) 1 else 2
                        startBrightness = window.attributes.screenBrightness
                        if (startBrightness < 0f) startBrightness = 0.5f
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    if (dragging) {
                        val delta = -dy / v.height.toFloat()
                        if (dragSide == 1) {
                            val value = (startBrightness + delta).coerceIn(0.02f, 1f)
                            val attrs = window.attributes
                            attrs.screenBrightness = value
                            window.attributes = attrs
                            flashHud(getString(R.string.hud_brightness, (value * 100).toInt()))
                        } else {
                            val value = (startVolume + delta * maxVolume).toInt()
                                .coerceIn(0, maxVolume)
                            if (value != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                            }
                            flashHud(getString(R.string.hud_volume, value * 100 / maxVolume))
                        }
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        v.removeCallbacks(hudRunnable)
                        v.postDelayed(hudRunnable, 600)
                        return@setOnTouchListener true
                    }
                }
            }
            true
        }
    }

    private fun flashHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hudRunnable)
        hud.postDelayed(hudRunnable, 1200)
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
