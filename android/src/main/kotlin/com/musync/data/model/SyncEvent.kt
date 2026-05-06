package com.musync.data.model

sealed class SyncEvent {
    data class PlayNext(val sessionId: String) : SyncEvent()

    data class Play(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Pause(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Seek(val sessionId: String, val positionMs: Long) : SyncEvent()

    /** Emitted when the host ends the session for all participants. */
    data object RoomClosed : SyncEvent()

    /** Emitted when a remote peer joins the room. */
    data object PeerJoined : SyncEvent()

    /** Emitted when a remote peer leaves the room. */
    data object PeerLeft : SyncEvent()

    /**
     * Emitted once when joining a room, providing the initial total member count
     * (including the local user).
     */
    data class MembersSnapshot(val count: Int) : SyncEvent()
}
