package com.thornex.musicparty

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * 前台服务：让系统把 app 视为"正在播放媒体"，退后台/锁屏时不节流、不杀进程。
 * 前台通知复用 MediaSessionManager 的媒体通知（同一个 NOTIF_ID），不重复。
 * 音频本体在 WebView 里，本服务只负责保活 + 承载媒体通知。
 */
class MediaPlaybackService : Service() {

    override fun onCreate() {
        super.onCreate()
        MediaSessionManager.init(this)
        ServiceCompat.startForeground(this, MediaSessionManager.NOTIF_ID,
            MediaSessionManager.currentNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
