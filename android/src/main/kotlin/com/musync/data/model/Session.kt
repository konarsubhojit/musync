package com.musync.data.model

data class Session(
    val sessionId: String,
    val hostId: String,
    val localUserId: String,
    /** The display name chosen by this client for this session. */
    val displayName: String = "",
)
