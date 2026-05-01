package com.musync.sync

import com.musync.data.model.SyncHeartbeat
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.socket.client.Socket

/**
 * Unit tests for [SyncManager] drift-correction logic.
 *
 * The socket is mocked so these tests run on the JVM without an Android runtime.
 * A [FakeClock] is used to control wall-clock time deterministically.
 */
class SyncManagerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private class FakeClock(var timeMs: Long) : Clock {
        override fun currentTimeMs() = timeMs
    }

    private lateinit var fakeClock: FakeClock
    private lateinit var sut: SyncManager

    private var localPositionMs: Long = 0L
    private var seekedToMs: Long? = null

    @Before
    fun setUp() {
        fakeClock = FakeClock(timeMs = 0L)
        sut = SyncManager(socket = mockk<Socket>(relaxed = true), clock = fakeClock)
        sut.attachPlayer(
            getPosition = { localPositionMs },
            onSeek = { seekedToMs = it },
        )
    }

    // ── no-seek scenarios ─────────────────────────────────────────────────────

    @Test
    fun `no seek when drift is exactly zero`() {
        // host sent position 10 000 ms, timestamp 1 000 ms ago; local is in sync
        fakeClock.timeMs = 1_000L
        localPositionMs = 11_000L          // 10 000 + 1 000 elapsed = 11 000

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 10_000L, hostTimestamp = 0L))

        assertEquals("seekTo should not be called for zero drift", null, seekedToMs)
    }

    @Test
    fun `no seek when drift is under threshold (1 s)`() {
        fakeClock.timeMs = 1_000L
        localPositionMs = 11_500L          // drift = +500 ms (under 2 s)

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 10_000L, hostTimestamp = 0L))

        assertEquals(null, seekedToMs)
    }

    @Test
    fun `no seek when drift equals threshold exactly (2 s)`() {
        fakeClock.timeMs = 1_000L
        localPositionMs = 13_000L          // drift = +2 000 ms (not strictly > 2 s)

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 10_000L, hostTimestamp = 0L))

        assertEquals(null, seekedToMs)
    }

    // ── seek scenarios ────────────────────────────────────────────────────────

    @Test
    fun `seek when local player is more than 2 s ahead of host`() {
        fakeClock.timeMs = 1_000L
        // estimated host position = 10 000 + 1 000 = 11 000 ms
        localPositionMs = 13_001L          // drift = +3 001 ms (> 2 s)

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 10_000L, hostTimestamp = 0L))

        assertEquals("should seek to estimated host position", 11_000L, seekedToMs)
    }

    @Test
    fun `seek when local player is more than 2 s behind host`() {
        fakeClock.timeMs = 1_000L
        // estimated host position = 10 000 + 1 000 = 11 000 ms
        localPositionMs = 8_999L           // drift = -2 001 ms (abs > 2 s)

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 10_000L, hostTimestamp = 0L))

        assertEquals("should seek to estimated host position", 11_000L, seekedToMs)
    }

    @Test
    fun `seek compensates for network latency in estimated host position`() {
        // Host sent heartbeat at t=500. Local clock is now at t=1 000 → 500 ms in transit.
        // estimated host position = 20 000 + (1 000 - 500) = 20 500 ms
        fakeClock.timeMs = 1_000L
        localPositionMs = 50_000L          // wildly out of sync

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 20_000L, hostTimestamp = 500L))

        assertEquals(20_500L, seekedToMs)
    }

    @Test
    fun `seek target is estimated host position not raw host position`() {
        // Ensure that the latency compensation affects the seek target
        fakeClock.timeMs = 3_000L
        // estimated = 5 000 + (3 000 - 0) = 8 000
        localPositionMs = 0L               // drift = -8 000 ms

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 5_000L, hostTimestamp = 0L))

        assertEquals(8_000L, seekedToMs)
    }

    // ── boundary ──────────────────────────────────────────────────────────────

    @Test
    fun `no seek when drift is 1 ms below threshold`() {
        fakeClock.timeMs = 0L
        localPositionMs = 1_999L           // drift = +1 999 ms

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 0L, hostTimestamp = 0L))

        assertEquals(null, seekedToMs)
    }

    @Test
    fun `seek when drift is 1 ms above threshold`() {
        fakeClock.timeMs = 0L
        localPositionMs = 2_001L           // drift = +2 001 ms

        sut.handleHeartbeat(SyncHeartbeat(hostPositionMs = 0L, hostTimestamp = 0L))

        assertEquals(0L, seekedToMs)
    }
}
