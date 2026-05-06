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

    /**
     * Ends the session for all participants (host only).
     *
     * Emits an `end_session` socket event so the server broadcasts `ROOM_CLOSED`
     * to every member.  After calling this, the caller should navigate away —
     * the local session state is cleared when `ROOM_CLOSED` is received.
     */
    fun endSession()

    /**
     * Transfers the host role to the participant identified by [newHostSocketId].
     *
     * Emits a `TRANSFER_HOST` socket event.  The server validates that the local
     * user is the current host, then broadcasts `HOST_TRANSFERRED` to all room
     * members.  Only the current host may call this; calling it as a guest is
     * a no-op (the server will silently reject the event).
     *
     * @param roomId         The session / room identifier.
     * @param newHostSocketId The socket ID of the participant to promote to host.
     */
    fun transferHost(roomId: String, newHostSocketId: String)
}
