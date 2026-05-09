package com.musync.playback

import android.content.Context
import android.content.Intent
import android.os.Build
import com.musync.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bridge between the in-app YouTube player surface and the
 * out-of-process media notification owned by [MediaPlaybackService].
 *
 * The controller is a stateless coordinator:
 *  - The active [PlayerScreen] (via its `PlayerViewModel` / Compose surface)
 *    publishes the current [TrackInfo] and [isPlaying] state through
 *    [updateTrack] / [updatePlaybackState], and registers playback
 *    [Listener]s via [setListener] so notification actions can be applied
 *    to the local YouTube player.
 *  - The [MediaPlaybackService] reads from the same flows to render its
 *    `MediaSessionCompat` metadata + `NotificationCompat.MediaStyle`
 *    notification, and dispatches incoming `MediaSession` callbacks back
 *    through the same [Listener].
 *
 * This decoupling keeps the foreground service free of Compose / ViewModel
 * dependencies, and keeps the player UI free of Service / MediaSession
 * APIs — both sides only ever talk to this small façade.
 *
 * The class is a `@Singleton` provided by Hilt; the listener registration
 * follows last-writer-wins semantics, so when a new player session starts
 * the previous listener is implicitly replaced by [clearListener]/
 * [setListener] from the new screen.
 */
@Singleton
class MediaPlaybackController
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) {
        /** Minimal, serialisable description of the currently-loaded track. */
        data class TrackInfo(
            val id: String,
            val title: String,
            val artist: String,
            val durationMs: Long,
        )

        /**
         * Receives commands from the notification / lock-screen controls.
         * Implementations are expected to apply each command to the local
         * YouTube player on the main thread.
         */
        interface Listener {
            fun onPlay()

            fun onPause()

            fun onSkipNext()

            /** User dismissed the notification or otherwise stopped the session. */
            fun onStop()
        }

        private val _track = MutableStateFlow<TrackInfo?>(null)
        val track: StateFlow<TrackInfo?> = _track.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _positionMs = MutableStateFlow(0L)
        val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

        private val _hasNext = MutableStateFlow(false)
        val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

        @Volatile
        private var listener: Listener? = null

        /** Registers the active player surface as the receiver of notification actions. */
        fun setListener(listener: Listener) {
            this.listener = listener
        }

        /**
         * Clears [listener] when [current] is the registered listener.
         *
         * Using identity-based clearing prevents a teardown from a stale player
         * surface from accidentally erasing a listener registered by a newer one.
         */
        fun clearListener(current: Listener) {
            if (this.listener === current) {
                this.listener = null
            }
        }

        // ── Inbound commands (called by MediaPlaybackService) ──────────────

        internal fun dispatchPlay() {
            listener?.onPlay()
        }

        internal fun dispatchPause() {
            listener?.onPause()
        }

        internal fun dispatchSkipNext() {
            listener?.onSkipNext()
        }

        internal fun dispatchStop() {
            listener?.onStop()
        }

        // ── Outbound state (read by MediaPlaybackService) ──────────────────

        /**
         * Publishes the currently-loaded track and starts the foreground
         * service the first time a track is set so the notification appears.
         */
        fun updateTrack(track: TrackInfo?) {
            _track.value = track
            if (track != null) {
                ensureServiceStarted()
            }
        }

        fun updatePlaybackState(
            isPlaying: Boolean,
            positionMs: Long,
        ) {
            _isPlaying.value = isPlaying
            _positionMs.value = positionMs.coerceAtLeast(0L)
            if (_track.value != null) {
                ensureServiceStarted()
            }
        }

        fun updateHasNext(hasNext: Boolean) {
            _hasNext.value = hasNext
        }

        /**
         * Tears down the notification + foreground service when the player
         * surface goes away (e.g. the user leaves the room).  Safe to call
         * multiple times.
         */
        fun stop() {
            _track.value = null
            _isPlaying.value = false
            _positionMs.value = 0L
            _hasNext.value = false
            try {
                appContext.stopService(Intent(appContext, MediaPlaybackService::class.java))
            } catch (t: Throwable) {
                // Stopping a not-running service is a no-op; we still want
                // to defend against any unexpected RuntimeException so a
                // tear-down failure cannot crash the activity.
                AppLogger.w(TAG, "Failed to stop MediaPlaybackService", t)
            }
        }

        private fun ensureServiceStarted() {
            val intent = Intent(appContext, MediaPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (t: Throwable) {
                // On Android 12+ background service launches can throw
                // ForegroundServiceStartNotAllowedException; on other
                // versions a SecurityException is possible. We log and
                // continue rather than crash – the notification just
                // won't appear in that case.
                AppLogger.w(TAG, "Failed to start MediaPlaybackService", t)
            }
        }

        private companion object {
            const val TAG = "MediaPlaybackController"
        }
    }
