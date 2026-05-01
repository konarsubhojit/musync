package com.musync.data.model

sealed class SyncEvent {
    data class PlayNext(val sessionId: String) : SyncEvent()

    data class Play(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Pause(val sessionId: String, val positionMs: Long) : SyncEvent()

    data class Seek(val sessionId: String, val positionMs: Long) : SyncEvent()
}
