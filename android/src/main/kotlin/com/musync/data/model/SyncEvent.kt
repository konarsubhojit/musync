package com.musync.data.model

sealed class SyncEvent {
    data class PlayNext(val sessionId: String) : SyncEvent()

    data class Play(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Pause(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Seek(val sessionId: String, val positionMs: Long) : SyncEvent()

    /** Emitted when the host ends the session for all participants. */
    data object RoomClosed : SyncEvent()

    /** Emitted when the server broadcasts an updated participant list for the room. */
    data class ParticipantsUpdated(val participants: List<Participant>) : SyncEvent()
}
