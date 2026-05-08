package com.musync.data.model

sealed class SyncEvent {
    data class PlayNext(val sessionId: String) : SyncEvent()

    data class Play(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Pause(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Seek(val sessionId: String, val positionMs: Long) : SyncEvent()

    data object RoomClosed : SyncEvent()
    data object PeerJoined : SyncEvent()
    data object PeerLeft : SyncEvent()
    data class MembersSnapshot(val count: Int) : SyncEvent()
    /**
     * Emitted when the host role is transferred to a different participant.
     * @param isNowHost true when the local user is the newly promoted host.
     */
    data class HostTransferred(val isNowHost: Boolean) : SyncEvent()
    /** Emitted when the host toggles democratic mode (anyone can control playback). */
    data class DemocraticModeChanged(val enabled: Boolean) : SyncEvent()
    /** Emitted when the host toggles auto-approve for guest queue additions. */
    data class AutoApproveQueueChanged(val enabled: Boolean) : SyncEvent()
    /**
     * Emitted to the host when a guest requests to add a track to the queue
     * and autoApproveQueue is disabled.
     */
    data class QueueAddRequest(val trackId: String, val trackTitle: String) : SyncEvent()
}
