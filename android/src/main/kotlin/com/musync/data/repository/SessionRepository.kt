package com.musync.data.repository

import com.musync.data.model.ChatMessage
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

    /** A flow of chat messages received from other room members, plus echoes of sent messages. */
    val chatMessages: SharedFlow<ChatMessage>

    /** A flow of ephemeral emoji reactions received from other room members. */
    val reactions: SharedFlow<String>

    /**
     * A flow of user IDs currently typing in the room.
     *
     * Entries are removed automatically after a short inactivity window.
     */
    val typingUsers: StateFlow<Set<String>>

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
     * Sends a chat message to all other members of the current room.
     *
     * Also emits the message locally (with [ChatMessage.isLocal] = `true`) so
     * the sender sees their own message immediately without waiting for a server
     * echo.  No-op when there is no active session.
     *
     * @param text        The message text to send.
     * @param senderName  Display name shown alongside the message.
     */
    fun sendChatMessage(
        text: String,
        senderName: String,
    )

    /**
     * Broadcasts an ephemeral emoji reaction to all other members.
     *
     * No-op when there is no active session.
     *
     * @param emoji The emoji string to broadcast (e.g. "🔥").
     */
    fun sendReaction(emoji: String)

    /**
     * Notifies other room members that the local user is composing a message.
     *
     * No-op when there is no active session.
     *
     * @param senderName Display name of the typing user.
     */
    fun sendTyping(senderName: String)
}
