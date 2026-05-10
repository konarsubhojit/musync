package com.musync.data.repository

import com.musync.data.model.ChatMessage
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SessionRepository {
    val session: StateFlow<Session?>
    val events: SharedFlow<SyncEvent>
    val chatMessages: SharedFlow<ChatMessage>
    val reactions: SharedFlow<String>
    val typingUsers: StateFlow<Set<String>>

    fun onPlayerStateChanged(state: PlayerState)

    fun joinSession(session: Session)

    fun leaveSession()

    fun endSession()

    fun sendChatMessage(
        text: String,
        senderName: String,
    )

    fun sendReaction(emoji: String)

    fun sendTyping(senderName: String)

    /**
     * Transfers the host role to the participant identified by [newHostSocketId].
     * Only the current host may call this; the server validates and then broadcasts
     * HOST_TRANSFERRED to all room members.
     */
    fun transferHost(
        roomId: String,
        newHostSocketId: String,
    )

    /**
     * Enables or disables democratic mode for the room (host only).
     * When enabled, any room member may send PLAY/PAUSE/SEEK commands.
     * The server broadcasts DEMOCRATIC_MODE_CHANGED to all members.
     */
    fun setDemocraticMode(
        roomId: String,
        enabled: Boolean,
    )

    /**
     * Enables or disables auto-approval for guest queue addition requests (host only).
     * When disabled, guest requests are forwarded to the host for manual approval.
     * The server broadcasts AUTO_APPROVE_QUEUE_CHANGED to all members.
     */
    fun setAutoApproveQueue(
        roomId: String,
        enabled: Boolean,
    )

    /**
     * Requests to add a track to the room queue (guests use this in non-democratic mode).
     * The server either auto-approves (adds to queue and broadcasts QUEUE_UPDATED) or
     * forwards a QUEUE_ADD_REQUEST event to the host for manual approval.
     *
     * @param roomId     The session / room identifier.
     * @param trackId    A client-generated UUID identifying the request.
     * @param trackTitle Human-readable title of the track.
     */
    fun requestQueueAdd(
        roomId: String,
        trackId: String,
        trackTitle: String,
    )

    /**
     * Approves a guest queue addition request (host only).
     * The server adds the track to the queue and broadcasts QUEUE_UPDATED.
     *
     * @param roomId     The session / room identifier.
     * @param trackId    The track ID originally sent in the QUEUE_ADD_REQUEST.
     * @param trackTitle Human-readable title of the track.
     */
    fun approveQueueAdd(
        roomId: String,
        trackId: String,
        trackTitle: String,
    )
}
