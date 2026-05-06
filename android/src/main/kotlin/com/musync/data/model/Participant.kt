package com.musync.data.model

/**
 * Represents a participant in a listening session.
 *
 * @property socketId The server-assigned socket identifier for this participant.
 * @property displayName The human-readable name chosen by this participant.
 */
data class Participant(
    val socketId: String,
    val displayName: String,
)
