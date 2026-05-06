package com.musync.sync

import io.socket.client.Socket
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits outgoing playback sync events to the server on behalf of the session host.
 *
 * This class is the counterpart to [SyncManager]: while [SyncManager] *receives* incoming
 * heartbeats and corrects local drift, [SyncEmitter] *sends* the host's playback state so
 * that guests can synchronise to it.
 *
 * Only the host should call [emitPlay], [emitPause], [emitSeek], and [emitHeartbeat].
 * Guest clients must never emit these events.
 *
 * Expected server payloads:
 * - PLAY / PAUSE / SEEK : `{ roomId: String, positionMs: Long }`
 * - SYNC_HEARTBEAT      : `{ roomId: String, hostPositionMs: Long, hostTimestamp: Long }`
 */
@Singleton
class SyncEmitter
    @Inject
    constructor(
        private val socket: Socket,
        private val clock: Clock,
    ) {
        /** Emits a [SocketEvents.PLAY] event so guests start playback at [positionMs]. */
        fun emitPlay(
            roomId: String,
            positionMs: Long,
        ) {
            socket.emit(
                SocketEvents.PLAY,
                JSONObject()
                    .put("roomId", roomId)
                    .put("positionMs", positionMs),
            )
        }

        /** Emits a [SocketEvents.PAUSE] event so guests pause playback at [positionMs]. */
        fun emitPause(
            roomId: String,
            positionMs: Long,
        ) {
            socket.emit(
                SocketEvents.PAUSE,
                JSONObject()
                    .put("roomId", roomId)
                    .put("positionMs", positionMs),
            )
        }

        /** Emits a [SocketEvents.SEEK] event so guests seek to [positionMs]. */
        fun emitSeek(
            roomId: String,
            positionMs: Long,
        ) {
            socket.emit(
                SocketEvents.SEEK,
                JSONObject()
                    .put("roomId", roomId)
                    .put("positionMs", positionMs),
            )
        }

        /**
         * Emits a [SocketEvents.SYNC_HEARTBEAT] with the host's current position.
         *
         * The timestamp is captured from [clock] at the moment of emission so guests
         * can compensate for network transit time when correcting drift.
         */
        fun emitHeartbeat(
            roomId: String,
            positionMs: Long,
        ) {
            socket.emit(
                SocketEvents.SYNC_HEARTBEAT,
                JSONObject()
                    .put("roomId", roomId)
                    .put("hostPositionMs", positionMs)
                    .put("hostTimestamp", clock.currentTimeMs()),
            )
        }
    }
