package com.musync.sync

import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.logging.AppLogger
import io.socket.client.Socket
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for [SocketEvents.QUEUE_UPDATED] events from the server and updates the
 * [MusicRepository] queue accordingly.
 *
 * Call [startListening] to register the socket listener and [stopListening] to remove it.
 * Both methods are idempotent: calling [startListening] twice will not register duplicate
 * listeners because the previous listener is removed before registering a new one.
 */
@Singleton
class QueueManager
    @Inject
    constructor(
        private val socket: Socket,
        private val musicRepository: MusicRepository,
    ) {
        private val tag = "QueueManager"

        /** Registers the [SocketEvents.QUEUE_UPDATED] listener on the socket. */
        fun startListening() {
            AppLogger.i(tag, "startListening")
            // Remove any existing listener before registering to avoid duplicates.
            socket.off(SocketEvents.QUEUE_UPDATED)
            socket.on(SocketEvents.QUEUE_UPDATED) { args ->
                val json = args.firstOrNull() as? JSONArray ?: return@on
                val tracks = parseQueue(json)
                AppLogger.i(tag, "recv QUEUE_UPDATED parsedCount=${tracks.size}")
                musicRepository.updateQueue(tracks)
            }
        }

        /** Removes the [SocketEvents.QUEUE_UPDATED] listener from the socket. */
        fun stopListening() {
            AppLogger.i(tag, "stopListening")
            socket.off(SocketEvents.QUEUE_UPDATED)
        }

        /**
         * Parses a JSON array of track objects into a [List] of [Track].
         *
         * Expected JSON structure per element:
         * ```json
         * { "id": "1", "title": "Song", "artist": "Artist", "youtubeVideoId": "abc", "durationMs": 180000 }
         * ```
         * Elements that are missing required fields (empty `id` or `youtubeVideoId`) are skipped.
         */
        internal fun parseQueue(json: JSONArray): List<Track> {
            val tracks = mutableListOf<Track>()
            for (i in 0 until json.length()) {
                val obj = json.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val youtubeVideoId = obj.optString("youtubeVideoId")
                if (id.isEmpty() || youtubeVideoId.isEmpty()) continue
                tracks.add(
                    Track(
                        id = id,
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        youtubeVideoId = youtubeVideoId,
                        durationMs = obj.optLong("durationMs"),
                    ),
                )
            }
            return tracks
        }
    }
