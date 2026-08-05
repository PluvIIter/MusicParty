package com.thornex.musicparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 前台服务：让系统把 app 视为"正在播放媒体"，退后台/锁屏时不节流、不杀进程。
 * 音频本体在 WebView 里，本服务只负责保活 + 常驻通知。
 */
class MediaPlaybackService : Service() {

    override fun onCreate() {
        super.onCreate()
        // NotificationChannel 是 API 26+ 才有的，minSdk 24 需守卫
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // Android 10+ 起 startForeground 需带前台服务类型，ServiceCompat 兼容跨版本
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Party")
            .setContentText("后台播放中…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIF_ID = 1
    }
}
