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
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Owns the process-level MediaSession used by the foreground playback service. */
object MediaSessionManager {

    const val NOTIF_ID = 1

    private const val CHANNEL_ID = "media_session"
    private const val MAX_DURATION_SECONDS = 24 * 60 * 60L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkGeneration = AtomicLong(0)
    private var artworkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var session: MediaSessionCompat? = null
    @Volatile
    private var notification: Notification? = null

    /** Notification controls are forwarded to the currently visible Activity, when present. */
    @Volatile
    var onControl: ((String) -> Unit)? = null

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (session != null) return
            if (artworkExecutor.isShutdown) artworkExecutor = Executors.newSingleThreadExecutor()

            if (Build.VERSION.SDK_INT >= 26) {
                val nm = appContext!!.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Media Session", NotificationManager.IMPORTANCE_LOW)
                )
            }

            val context = appContext ?: return
            session = MediaSessionCompat(context, "MusicPartySession").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { onControl?.invoke("play") }
                    override fun onPause() { onControl?.invoke("pause") }
                    override fun onSkipToNext() { onControl?.invoke("next") }
                })
                isActive = true
            }
            notification = buildNotificationLocked("Music Party", "", false, null)
        }
    }

    fun currentNotification(): Notification {
        synchronized(lock) {
            return notification ?: buildNotificationLocked("Music Party", "", false, null)
        }
    }

    /** Updates the native session only; it never changes playback in the room. */
    fun update(json: String) {
        val values = runCatching { JSONObject(json) }.getOrNull() ?: return
        val title = values.optString("title").takeIf { it.isNotBlank() } ?: return
        val artist = values.optString("artist")
        val durationSec = values.optLong("duration", 0L).coerceIn(0L, MAX_DURATION_SECONDS)
        val positionSec = values.optLong("position", 0L).coerceIn(0L, durationSec)
        val paused = values.optBoolean("paused", false)
        val coverUrl = values.optString("coverUrl")
        val generation = artworkGeneration.incrementAndGet()

        synchronized(lock) {
            val active = session ?: return
            active.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
                .build())
            active.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
                .setState(
                    if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                    positionSec * 1000L,
                    1f
                )
                .build())
            notification = buildNotificationLocked(title, artist, paused, null)
            appContext?.let { NotificationManagerCompat.from(it).notify(NOTIF_ID, notification!!) }
        }

        if (coverUrl.isNotBlank()) loadArtworkAsync(coverUrl, title, artist, paused, generation)
    }

    fun destroy() {
        artworkGeneration.incrementAndGet()
        synchronized(lock) {
            onControl = null
            session?.isActive = false
            session?.release()
            session = null
            notification = null
            appContext = null
            artworkExecutor.shutdownNow()
        }
    }

    private fun loadArtworkAsync(
        coverUrl: String,
        title: String,
        artist: String,
        paused: Boolean,
        generation: Long,
    ) {
        artworkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(coverUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Referer", "https://music.163.com/")
                val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@execute
                if (artworkGeneration.get() != generation) return@execute
                mainHandler.post {
                    synchronized(lock) {
                        val active = session ?: return@synchronized
                        if (artworkGeneration.get() != generation) return@synchronized
                        val current = active.controller.metadata
                        active.setMetadata((if (current != null) MediaMetadataCompat.Builder(current)
                        else MediaMetadataCompat.Builder())
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                            .build())
                        notification = buildNotificationLocked(title, artist, paused, bitmap)
                        appContext?.let { NotificationManagerCompat.from(it).notify(NOTIF_ID, notification!!) }
                    }
                }
            } catch (_: Exception) {
                // Artwork failure must not affect playback controls.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun buildNotificationLocked(
        title: String,
        artist: String,
        paused: Boolean,
        art: Bitmap?,
    ): Notification {
        val context = appContext ?: error("MediaSessionManager is not initialized")
        val active = session ?: error("MediaSessionManager has no active session")
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = MediaStyle()
            .setMediaSession(active.sessionToken)
            .setShowActionsInCompactView(0, 1)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(style)
        if (art != null) builder.setLargeIcon(art)
        builder.addAction(mediaAction(
            context,
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "播放" else "暂停",
            if (paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE,
        ))
        builder.addAction(mediaAction(context, android.R.drawable.ic_media_next, "下一首", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        return builder.build()
    }

    private fun mediaAction(context: Context, icon: Int, title: String, action: Long): NotificationCompat.Action =
        NotificationCompat.Action(icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(context, action))
}
