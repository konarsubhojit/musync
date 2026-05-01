package com.musync.sync

import com.musync.data.model.SyncHeartbeat
import io.socket.client.Socket
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Manages loose playback synchronisation with the session host.
 *
 * When a [SocketEvents.SYNC_HEARTBEAT] event is received from the server the manager:
 * 1. Estimates the host's current playback position, compensating for network transit time.
 * 2. Compares that estimate to the local player position.
 * 3. Seeks the local player if the drift exceeds [SEEK_THRESHOLD_MS] (2 seconds).
 *
 * Attach the local player controls via [attachPlayer] before calling [startListening].
 */
@Singleton
class SyncManager
    @Inject
    constructor(
        private val socket: Socket,
        private val clock: Clock,
    ) {
        companion object {
            /** Minimum absolute drift (ms) that triggers a seek correction. */
            const val SEEK_THRESHOLD_MS = 2_000L
        }

        private var localPosition: () -> Long = { 0L }
        private var seekTo: (Long) -> Unit = {}

        /**
         * Provides the [SyncManager] with callbacks to read / control the local player.
         *
         * @param getPosition Lambda that returns the current playback position in milliseconds.
         * @param onSeek      Lambda that seeks the local player to the given position in milliseconds.
         */
        fun attachPlayer(
            getPosition: () -> Long,
            onSeek: (Long) -> Unit,
        ) {
            localPosition = getPosition
            seekTo = onSeek
        }

        /** Registers the [SocketEvents.SYNC_HEARTBEAT] listener on the socket. */
        fun startListening() {
            socket.on(SocketEvents.SYNC_HEARTBEAT) { args ->
                val json = args.firstOrNull() as? JSONObject ?: return@on
                val heartbeat =
                    SyncHeartbeat(
                        hostPositionMs = json.optLong("hostPositionMs"),
                        hostTimestamp = json.optLong("hostTimestamp"),
                    )
                handleHeartbeat(heartbeat)
            }
        }

        /** Removes the [SocketEvents.SYNC_HEARTBEAT] listener from the socket. */
        fun stopListening() {
            socket.off(SocketEvents.SYNC_HEARTBEAT)
        }

        /**
         * Core drift-correction logic, separated from socket parsing so it can be unit-tested
         * without an Android runtime or a real socket.
         *
         * The estimated host position accounts for the time elapsed since the heartbeat was sent:
         * ```
         * estimatedHostPosition = hostPositionMs + (localNow - hostTimestamp)
         * ```
         * If `|localPosition - estimatedHostPosition| > SEEK_THRESHOLD_MS` a seek is issued.
         */
        internal fun handleHeartbeat(heartbeat: SyncHeartbeat) {
            val now = clock.currentTimeMs()
            val estimatedHostPosition = heartbeat.hostPositionMs + (now - heartbeat.hostTimestamp)
            val drift = localPosition() - estimatedHostPosition

            if (abs(drift) > SEEK_THRESHOLD_MS) {
                seekTo(estimatedHostPosition)
            }
        }
    }
