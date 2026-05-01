package com.musync.data.repository

import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    /** Returns a flow of the current session, or null when not in a session. */
    fun getSession(): Flow<Session?>

    /** Returns a flow of outgoing sync events to be forwarded to the server. */
    fun getEvents(): Flow<SyncEvent>

    /**
     * Called whenever the player state changes.
     *
     * When the state transitions to [PlayerState.ENDED] and the local user is
     * the session host, a [SyncEvent.PlayNext] event is emitted exactly once
     * per track to prevent duplicate triggers.  The guard is reset when the
     * player transitions to [PlayerState.PLAYING], signalling that a new track
     * has started.
     */
    fun onPlayerStateChanged(state: PlayerState)

    /** Joins (or creates) a session. */
    fun joinSession(session: Session)

    /** Leaves the current session. */
    fun leaveSession()
}
