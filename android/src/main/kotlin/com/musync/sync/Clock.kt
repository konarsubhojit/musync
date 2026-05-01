package com.musync.sync

/**
 * Abstraction over wall-clock time so that [SyncManager] can be tested with a fake clock.
 */
fun interface Clock {
    fun currentTimeMs(): Long
}

/** Production implementation backed by [System.currentTimeMillis]. */
object SystemClock : Clock {
    override fun currentTimeMs(): Long = System.currentTimeMillis()
}
