package com.kirufetch.app.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadManager.ensureChannel(this)
        startForegroundSafely()
    }

    /**
     * 本服务只负责三件事：申请前台、维持进程优先级、让系统不冻结下载。
     * 真正的下载由 DownloadManager 的协程执行，这里不重复跑任务。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TASK_ID)
        if (id.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeTasks.add(id)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeTasks.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID)
            .setContentTitle("KiruFetch")
            .setContentText("正在下载")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    companion object {
        private const val NOTIFY_ID = 1002
        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // Android 12+ 后台启动前台服务受限，下载仍由进程内协程继续
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
