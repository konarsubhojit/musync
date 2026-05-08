package com.musync.data.model

/**
 * Represents a room that the local user previously joined or created.
 *
 * @property roomId      The unique room identifier.
 * @property displayName A human-readable label (defaults to the roomId).
 * @property lastJoinedAt Wall-clock timestamp (ms since epoch) of the last visit.
 */
data class RecentRoom(
    val roomId: String,
    val displayName: String,
    val lastJoinedAt: Long,
)
