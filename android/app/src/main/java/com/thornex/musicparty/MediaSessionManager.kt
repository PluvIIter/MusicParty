package com.thornex.musicparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.MediaMetadataCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通知栏/锁屏媒体控制器。
 * Web 端通过 JS 桥把 {title,artist,position,duration,paused,coverUrl} 推到这里，
 * 原生建立 MediaSession + MediaStyle 通知；通知按钮经 MediaButtonReceiver
 * 触发 session 回调 → onControl 桥回 Web 端执行播放/暂停/下一首。
 */
object MediaSessionManager {

    const val NOTIF_ID = 1   // 与前台服务共用同一通知 ID，避免双通知

    private const val CHANNEL_ID = "media_session"

    private lateinit var appContext: Context
    private var session: MediaSessionCompat? = null
    private var notification: Notification? = null

    /** Web 端控制回调：通知按钮 → 执行 Web 端动作（由 MainActivity 注入） */
    var onControl: ((String) -> Unit)? = null

    /** 初始化（幂等）：建 session + 频道 + 初始通知。由 Service 与 MainActivity 都可调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (session != null) return

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media Session", NotificationManager.IMPORTANCE_LOW)
            )
        }

        session = MediaSessionCompat(appContext, "MusicPartySession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { onControl?.invoke("play") }
                override fun onPause() { onControl?.invoke("pause") }
                override fun onSkipToNext() { onControl?.invoke("next") }
            })
            isActive = true
        }
        notification = buildNotification("Music Party", "", false, null)
    }

    /** 前台服务启动时需要的当前通知 */
    fun currentNotification(): Notification = notification
        ?: NotificationCompat.Builder(appContext, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play).build()

    /** 由 JS 桥调用：Web 端推送播放状态 */
    fun update(json: String) {
        val s = session ?: return
        val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val title = o.optString("title", "Music Party")
        val artist = o.optString("artist", "")
        val positionSec = o.optLong("position", 0L)
        val durationSec = o.optLong("duration", 0L)
        val paused = o.optBoolean("paused", false)
        val coverUrl = o.optString("coverUrl", "")

        s.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
            .build())

        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
            .setState(
                if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                positionSec * 1000L, 1f
            )
            .build())

        notification = buildNotification(title, artist, paused, null)
        NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification!!)

        // 异步加载封面作大图（失败不影响控制）
        if (coverUrl.isNotEmpty()) loadArtworkAsync(coverUrl, title, artist, paused)
    }

    fun destroy() {
        onControl = null
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun loadArtworkAsync(coverUrl: String, title: String, artist: String, paused: Boolean) {
        Thread {
            var stream: java.io.InputStream? = null
            try {
                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Referer", "https://music.163.com/")
                stream = conn.inputStream
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    val s = session ?: return@Thread
                    val meta = s.controller.metadata?.buildUpon()
                        ?.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                        ?.build()
                    if (meta != null) s.setMetadata(meta)
                    notification = buildNotification(title, artist, paused, bmp)
                    NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification!!)
                }
            } catch (e: Exception) {
                // 封面加载失败不影响控制
            } finally {
                try { stream?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun buildNotification(title: String, artist: String, paused: Boolean, art: Bitmap?): Notification {
        val contentIntent = PendingIntent.getActivity(appContext, 0,
            Intent(appContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        val style = MediaStyle()
            .setMediaSession(session!!.sessionToken)
            .setShowActionsInCompactView(0, 1)

        val b = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(style)

        if (art != null) b.setLargeIcon(art)

        b.addAction(mediaAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "播放" else "暂停",
            if (paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE))
        b.addAction(mediaAction(android.R.drawable.ic_media_next, "下一首", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))

        return b.build()
    }

    private fun mediaAction(icon: Int, title: String, action: Long): NotificationCompat.Action {
        val pi = MediaButtonReceiver.buildMediaButtonPendingIntent(appContext, action)
        return NotificationCompat.Action(icon, title, pi)
    }
}
