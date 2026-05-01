package com.musync.data.repository

import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SessionRepository {
    /** A flow of the current session, or null when not in a session. */
    val session: StateFlow<Session?>

    /** A flow of outgoing sync events to be forwarded to the server. */
    val events: SharedFlow<SyncEvent>

    /**
     * Called whenever the player state changes.
     *
     * When the state transitions to [PlayerState.ENDED] and the local user is
     * the session host, a [SyncEvent.PlayNext] event is emitted exactly once
     * per track to prevent duplicate triggers.  The guard is reset when the
     * player transitions to [PlayerState.PLAYING], signalling that a new track
     * has started, and also when the session context changes (for example,
     * joining, leaving, or reconnecting to a session).
     */
    fun onPlayerStateChanged(state: PlayerState)

    /** Joins (or creates) a session. */
    fun joinSession(session: Session)

    /** Leaves the current session. */
    fun leaveSession()
}
