package com.musync.ui.home

import com.musync.data.model.Track

/** UI state for [HomeViewModel]. */
data class HomeUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    /** Whether the inline "Join Room" input is expanded. */
    val joinExpanded: Boolean = false,
    /** Current text in the join-room input field. */
    val joinInput: String = "",
    /** True when the user attempted to join with text that didn't parse to a room ID. */
    val joinError: Boolean = false,
    /** A successfully parsed room ID, ready for the screen to navigate with. */
    val pendingJoinRoomId: String? = null,
)
