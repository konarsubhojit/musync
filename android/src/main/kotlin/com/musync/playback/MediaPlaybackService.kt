package com.musync.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.musync.MainActivity
import com.musync.R
import com.musync.logging.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that exposes the in-app YouTube playback as a system
 * media session and renders a media-style notification with Play / Pause /
 * Skip / Stop controls (#47).
 *
 * The service does **not** own any audio output of its own — actual playback
 * still happens in the activity's `YouTubePlayerView`.  Its responsibilities
 * are limited to:
 *  1. Hosting a [MediaSessionCompat] that publishes the current track's
 *     metadata + [PlaybackStateCompat] so the lock-screen / Bluetooth /
 *     Auto surfaces can render media controls.
 *  2. Posting a [NotificationCompat.MediaStyle] notification whose action
 *     buttons broadcast `MediaButton` intents back into the session.
 *  3. Keeping the app process alive while the user has the screen
 *     backgrounded so the YouTube WebView is not aggressively reaped by
 *     the system.
 *
 * Inbound media-button events are delivered into [mediaSessionCallback],
 * which forwards them to [MediaPlaybackController] for the active player
 * surface to apply.
 */
@AndroidEntryPoint
class MediaPlaybackService : android.app.Service() {
    @Inject
    lateinit var controller: MediaPlaybackController

    private lateinit var mediaSession: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectorJob: Job? = null

    private val mediaSessionCallback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                controller.dispatchPlay()
            }

            override fun onPause() {
                controller.dispatchPause()
            }

            override fun onSkipToNext() {
                controller.dispatchSkipNext()
            }

            override fun onStop() {
                controller.dispatchStop()
                stopSelfAndCleanup()
            }
        }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        mediaSession =
            MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
                setCallback(mediaSessionCallback)
                isActive = true
            }
        observeControllerState()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Forward any media-button intent (Bluetooth headset play/pause, etc.)
        // into the session callback above.
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        // Always start in foreground so we don't trip the 5-second deadline.
        // The notification reflects whatever the controller has published so
        // far — usually a track + paused state.
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Subscribes to controller state changes and re-renders the session
     * metadata + playback state + notification on every emission.
     */
    private fun observeControllerState() {
        stateCollectorJob?.cancel()
        stateCollectorJob =
            scope.launch {
                combine(
                    controller.track,
                    controller.isPlaying,
                    controller.positionMs,
                    controller.hasNext,
                ) { track, playing, position, hasNext ->
                    State(track, playing, position, hasNext)
                }.collect { state ->
                    if (state.track == null) {
                        // Nothing playing → tear ourselves down.
                        stopSelfAndCleanup()
                        return@collect
                    }
                    applyState(state)
                }
            }
    }

    private data class State(
        val track: MediaPlaybackController.TrackInfo?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val hasNext: Boolean,
    )

    private fun applyState(state: State) {
        val track = state.track ?: return

        val metadata =
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
                .build()
        mediaSession.setMetadata(metadata)

        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                (if (state.hasNext) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L)
        val playbackState =
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    state.positionMs,
                    if (state.isPlaying) 1f else 0f,
                )
                .build()
        mediaSession.setPlaybackState(playbackState)

        val notification = buildNotification()
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val track = controller.track.value
        val title = track?.title?.ifBlank { getString(R.string.app_name) } ?: getString(R.string.app_name)
        val artist =
            track?.artist?.takeIf { it.isNotBlank() }
                ?: getString(R.string.media_notification_default_subtitle)
        val isPlaying = controller.isPlaying.value
        val hasNext = controller.hasNext.value

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val playPauseAction =
            if (isPlaying) {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.media_notification_action_pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PAUSE,
                    ),
                )
            } else {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    getString(R.string.media_notification_action_play),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PLAY,
                    ),
                )
            }

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(isPlaying)
                .setDeleteIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_STOP,
                    ),
                )
                .addAction(playPauseAction)

        // Indices of the actions we want shown as compact session controls
        // ([0] = play/pause, optionally [1] = skip-next).
        val compactActionIndices: IntArray
        if (hasNext) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    getString(R.string.media_notification_action_skip_next),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                    ),
                ),
            )
            compactActionIndices = intArrayOf(0, 1)
        } else {
            compactActionIndices = intArrayOf(0)
        }

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(*compactActionIndices)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_STOP,
                    ),
                ),
        )

        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.media_notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        nm.createNotificationChannel(channel)
    }

    private fun stopSelfAndCleanup() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "stopForeground failed", t)
        }
        stopSelf()
    }

    private companion object {
        const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "musync_playback"
        const val NOTIFICATION_ID = 1001
        const val MEDIA_SESSION_TAG = "MuSyncMediaSession"
    }
}
