package com.musync.data.repository

import com.musync.data.model.Track
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    val currentTrack: Flow<Track?>

    val queue: Flow<List<Track>>

    fun updateQueue(tracks: List<Track>)

    /**
     * Appends [track] to the end of the local queue.
     *
     * This is a client-side mutation and does not propagate to the server.
     * Server-driven queue state will overwrite local additions on the next
     * [updateQueue] call (e.g. when a [com.musync.sync.SocketEvents.QUEUE_UPDATED]
     * event is received).
     */
    fun addToQueue(track: Track)
}
