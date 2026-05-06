package com.musync.data.model

/**
 * Represents a single chat message sent within a listening room.
 *
 * @param id         Locally generated unique identifier for this message.
 * @param senderId   The user ID of the sender.
 * @param senderName Display name of the sender.
 * @param text       The message text content.
 * @param timestamp  Wall-clock time when the message was created (ms since epoch).
 * @param isLocal    `true` when this message was sent by the local user.
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isLocal: Boolean,
)
