package com.tvip.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.tvip.proxy.action.START"
        const val ACTION_STOP = "com.tvip.proxy.action.STOP"
        const val CHANNEL_ID = "tvproxy_channel"
        const val NOTIFICATION_ID = 1

        @Volatile
        var lastStatus: String = "未启动"
            private set

        var statusListener: ((String) -> Unit)? = null

        private fun updateStatus(text: String) {
            lastStatus = text
            statusListener?.invoke(text)
        }
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("运行中"))

        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    ProxyManager.stop(applicationContext)
                    updateStatus("已关闭")
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                }
            }
            else -> {
                val url = SettingsStore.getSubscriptionUrl(applicationContext)
                if (url.isNullOrBlank()) {
                    updateStatus("尚未设置订阅地址")
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                } else {
                    scope.launch {
                        try {
                            ProxyManager.start(applicationContext, url) { status ->
                                updateStatus(status)
                                updateNotification(status)
                            }
                            // 恢复上次选择的节点（如果之前选过）
                            SettingsStore.getSelectedNode(applicationContext)?.let { node ->
                                ProxyManager.listNodes()?.let { (group, _) ->
                                    ProxyManager.selectNode(group, node)
                                }
                            }
                        } catch (e: Exception) {
                            updateStatus("启动失败: ${e.message}")
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "电视代理运行状态", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电视代理")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
