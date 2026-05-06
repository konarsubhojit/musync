package com.musync.data.repository

import com.musync.data.model.RecentRoom

/** Persists the list of rooms a user has previously joined. */
interface RecentRoomsRepository {
    /** Returns rooms sorted by [RecentRoom.lastJoinedAt] descending (most recent first). */
    fun getRecentRooms(): List<RecentRoom>

    /**
     * Inserts or moves [roomId] to the top of the history list with the
     * current timestamp.  Trims the list to [MAX_ROOMS] entries.
     */
    fun addOrUpdateRoom(
        roomId: String,
        displayName: String,
    )

    /** Removes all entries from the history. */
    fun clearHistory()

    companion object {
        /** Maximum number of entries retained in the history. */
        const val MAX_ROOMS = 20
    }
}
