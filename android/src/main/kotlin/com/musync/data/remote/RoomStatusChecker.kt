package com.musync.data.remote

/**
 * The live status of a room as reported by the server's
 * `GET /room/:roomId/status` endpoint.
 *
 * @property active        `true` when at least one listener is connected.
 * @property listenerCount Number of currently connected sockets in the room.
 */
data class RoomStatus(
    val active: Boolean,
    val listenerCount: Int,
)

/** Queries the server for the live status of a room. */
interface RoomStatusChecker {
    /**
     * Returns the current [RoomStatus] for [roomId], or `null` when the
     * request fails (network error, unexpected response, etc.).
     */
    suspend fun getStatus(roomId: String): RoomStatus?
}
