package com.musync.data.model

/**
 * Payload delivered with the [com.musync.sync.SocketEvents.SYNC_HEARTBEAT] event.
 *
 * @param hostPositionMs Playback position of the host at the time the heartbeat was sent, in ms.
 * @param hostTimestamp  Wall-clock time on the host when the heartbeat was sent
 *                       (milliseconds since epoch), used to compensate for network latency.
 */
data class SyncHeartbeat(
    val hostPositionMs: Long,
    val hostTimestamp: Long,
)
