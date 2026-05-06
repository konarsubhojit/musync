package com.musync.sync

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.socket.client.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SyncEmitter].
 *
 * The socket is mocked so these tests run on the JVM without an Android runtime or a real
 * network connection.  A [FakeClock] gives deterministic timestamps.
 */
class SyncEmitterTest {
    private class FakeClock(val timeMs: Long) : Clock {
        override fun currentTimeMs() = timeMs
    }

    private lateinit var mockSocket: Socket
    private lateinit var sut: SyncEmitter

    private val fakeRoomId = "room-123"

    @Before
    fun setUp() {
        mockSocket = mockk(relaxed = true)
        sut = SyncEmitter(socket = mockSocket, clock = FakeClock(timeMs = 1_000L))
    }

    // ── emitPlay ─────────────────────────────────────────────────────────────

    @Test
    fun `emitPlay calls socket emit with PLAY event and correct payload`() {
        val payloadSlot = slot<JSONObject>()

        sut.emitPlay(fakeRoomId, positionMs = 5_000L)

        verify { mockSocket.emit(SocketEvents.PLAY, capture(payloadSlot)) }
        assertEquals(fakeRoomId, payloadSlot.captured.getString("roomId"))
        assertEquals(5_000L, payloadSlot.captured.getLong("positionMs"))
    }

    @Test
    fun `emitPlay passes zero positionMs when at start`() {
        val payloadSlot = slot<JSONObject>()

        sut.emitPlay(fakeRoomId, positionMs = 0L)

        verify { mockSocket.emit(SocketEvents.PLAY, capture(payloadSlot)) }
        assertEquals(0L, payloadSlot.captured.getLong("positionMs"))
    }

    // ── emitPause ────────────────────────────────────────────────────────────

    @Test
    fun `emitPause calls socket emit with PAUSE event and correct payload`() {
        val payloadSlot = slot<JSONObject>()

        sut.emitPause(fakeRoomId, positionMs = 12_000L)

        verify { mockSocket.emit(SocketEvents.PAUSE, capture(payloadSlot)) }
        assertEquals(fakeRoomId, payloadSlot.captured.getString("roomId"))
        assertEquals(12_000L, payloadSlot.captured.getLong("positionMs"))
    }

    // ── emitSeek ─────────────────────────────────────────────────────────────

    @Test
    fun `emitSeek calls socket emit with SEEK event and correct payload`() {
        val payloadSlot = slot<JSONObject>()

        sut.emitSeek(fakeRoomId, positionMs = 30_000L)

        verify { mockSocket.emit(SocketEvents.SEEK, capture(payloadSlot)) }
        assertEquals(fakeRoomId, payloadSlot.captured.getString("roomId"))
        assertEquals(30_000L, payloadSlot.captured.getLong("positionMs"))
    }

    // ── emitHeartbeat ─────────────────────────────────────────────────────────

    @Test
    fun `emitHeartbeat calls socket emit with SYNC_HEARTBEAT and correct payload`() {
        val payloadSlot = slot<JSONObject>()

        sut.emitHeartbeat(fakeRoomId, positionMs = 8_000L)

        verify { mockSocket.emit(SocketEvents.SYNC_HEARTBEAT, capture(payloadSlot)) }
        val payload = payloadSlot.captured
        assertEquals(fakeRoomId, payload.getString("roomId"))
        assertEquals(8_000L, payload.getLong("hostPositionMs"))
        assertEquals(1_000L, payload.getLong("hostTimestamp"))
    }

    @Test
    fun `emitHeartbeat captures clock timestamp at emission time`() {
        val clock = FakeClock(timeMs = 42_000L)
        val emitter = SyncEmitter(socket = mockSocket, clock = clock)
        val payloadSlot = slot<JSONObject>()

        emitter.emitHeartbeat(fakeRoomId, positionMs = 0L)

        verify { mockSocket.emit(SocketEvents.SYNC_HEARTBEAT, capture(payloadSlot)) }
        assertEquals(42_000L, payloadSlot.captured.getLong("hostTimestamp"))
    }
}
