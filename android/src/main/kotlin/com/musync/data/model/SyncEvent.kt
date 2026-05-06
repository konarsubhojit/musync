package com.musync.data.model

sealed class SyncEvent {
    data class PlayNext(val sessionId: String) : SyncEvent()

    data class Play(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Pause(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Seek(val sessionId: String, val positionMs: Long) : SyncEvent()

    /** Emitted when the host ends the session for all participants. */
    data object RoomClosed : SyncEvent()

    /**
     * Emitted when the host role is transferred to a different participant.
     *
     * @param isNowHost `true` when the local user is the newly promoted host;
     *                  `false` when the local user's host role has been revoked.
     */
    data class HostTransferred(val isNowHost: Boolean) : SyncEvent()
}
