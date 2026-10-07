package com.pedrolopes.ttsing.tts

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pedrolopes.ttsing.MainActivity
import com.pedrolopes.ttsing.R

/**
 * Keeps a YouTube video going with the screen off or the app in the background. Android
 * freezes a backgrounded app within seconds unless it runs a foreground service, which
 * silences the player mid-sentence; this is that service, plus the notification and
 * lock-screen play/pause that come with it.
 *
 * The player itself stays the video screen's WebView: this only keeps the process awake and
 * relays play/pause to it. It starts when the video first plays and ends with the screen.
 */
class VideoPlaybackService : Service() {

    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSessionCompat(this, "TTSing video").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = control(play = true)
                override fun onPause() = control(play = false)
            })
            isActive = true
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(ReadingService.CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Read-aloud playback controls"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_PAUSE) control(play = !playing)
        render()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        session.release()
        super.onDestroy()
    }

    private fun render() {
        session.setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title).build())
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPause = PendingIntent.getService(
            this,
            0,
            Intent(this, VideoPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ReadingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText("Video")
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                playPause,
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0),
            )
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val ACTION_PLAY_PAUSE = "com.pedrolopes.ttsing.VIDEO_PLAY_PAUSE"

        // Main thread only. Cleared by stop() when the video screen goes, so it never outlives it.
        @SuppressLint("StaticFieldLeak")
        private var player: WebView? = null
        private var instance: VideoPlaybackService? = null
        private var title = ""
        private var playing = false

        /** Whether the video is playing right now, as the page last reported. */
        val isPlaying: Boolean get() = playing

        /** The video screen's player, so the notification can pause and resume it. */
        fun attach(webView: WebView, videoTitle: String) {
            player = webView
            title = videoTitle
        }

        /**
         * The page reports each play and pause. The first play starts the service — always
         * with the app on screen, since that's the only place a video can be started.
         */
        fun onPlayerState(context: Context, isPlaying: Boolean) {
            playing = isPlaying
            instance?.render() ?: run {
                if (isPlaying) ContextCompat.startForegroundService(context, Intent(context, VideoPlaybackService::class.java))
            }
        }

        fun stop(context: Context) {
            player = null
            playing = false
            context.stopService(Intent(context, VideoPlaybackService::class.java))
        }

        private fun control(play: Boolean) {
            player?.evaluateJavascript("player.${if (play) "playVideo" else "pauseVideo"}()", null)
        }
    }
}
