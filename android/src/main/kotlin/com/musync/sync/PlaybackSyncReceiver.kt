package com.musync.sync

import io.socket.client.Socket
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for incoming PLAY, PAUSE, and SEEK events from the server on behalf of guest clients.
 *
 * This is the receiving counterpart to [SyncEmitter]: while the host emits these events via
 * [SyncEmitter], guests receive them here and apply the commands to the local player.
 *
 * Only guests should call [startListening] / [stopListening].  Hosts emit these events and
 * never receive them (the server relays only to *other* room members).
 *
 * Usage:
 * 1. Call [attachPlayer] to register the local player control lambdas.
 * 2. Call [startListening] to begin receiving commands from the server.
 * 3. Call [stopListening] when the guest leaves the room or the ViewModel is destroyed.
 *
 * Expected server payload per event (matches what [SyncEmitter] sends):
 * - PLAY  : `{ positionMs: Long }`
 * - PAUSE : `{ positionMs: Long }`
 * - SEEK  : `{ positionMs: Long }`
 */
@Singleton
class PlaybackSyncReceiver
    @Inject
    constructor(
        private val socket: Socket,
    ) {
        private var onPlay: (Long) -> Unit = {}
        private var onPause: (Long) -> Unit = {}
        private var onSeek: (Long) -> Unit = {}

        /**
         * Registers lambdas that control the local player.
         *
         * @param onPlay  Called with the host's position (ms) when a PLAY event is received.
         *                Should seek the local player to that position and start playback.
         * @param onPause Called with the host's position (ms) when a PAUSE event is received.
         *                Should seek the local player to that position and pause.
         * @param onSeek  Called with the new position (ms) when a SEEK event is received.
         */
        fun attachPlayer(
            onPlay: (Long) -> Unit,
            onPause: (Long) -> Unit,
            onSeek: (Long) -> Unit,
        ) {
            this.onPlay = onPlay
            this.onPause = onPause
            this.onSeek = onSeek
        }

        /**
         * Registers PLAY, PAUSE, and SEEK listeners on the socket.
         *
         * Idempotent: any previous listeners for these events are removed before re-registering.
         */
        fun startListening() {
            socket.off(SocketEvents.PLAY)
            socket.on(SocketEvents.PLAY) { args ->
                val json = args.firstOrNull() as? JSONObject ?: return@on
                onPlay(json.optLong("positionMs"))
            }

            socket.off(SocketEvents.PAUSE)
            socket.on(SocketEvents.PAUSE) { args ->
                val json = args.firstOrNull() as? JSONObject ?: return@on
                onPause(json.optLong("positionMs"))
            }

            socket.off(SocketEvents.SEEK)
            socket.on(SocketEvents.SEEK) { args ->
                val json = args.firstOrNull() as? JSONObject ?: return@on
                onSeek(json.optLong("positionMs"))
            }
        }

        /** Removes all PLAY, PAUSE, and SEEK listeners from the socket. */
        fun stopListening() {
            socket.off(SocketEvents.PLAY)
            socket.off(SocketEvents.PAUSE)
            socket.off(SocketEvents.SEEK)
        }
    }
