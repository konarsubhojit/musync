package com.musync.ui.home

import com.musync.data.model.RecentRoom
import com.musync.data.model.Track
import com.musync.data.remote.RoomStatus

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
    /** Rooms the user previously joined, ordered by most-recent-first. */
    val recentRooms: List<RecentRoom> = emptyList(),
    /**
     * Live status for each entry in [recentRooms], keyed by [RecentRoom.roomId].
     * `null` values mean the status hasn't loaded yet or the request failed.
     */
    val recentRoomsStatus: Map<String, RoomStatus?> = emptyMap(),
)
