package com.musync.sync

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackSyncReceiver].
 *
 * The socket is mocked so tests run on the JVM without an Android runtime or a real
 * network connection.  Captured socket listeners are invoked directly to simulate
 * server-side PLAY/PAUSE/SEEK events arriving at the guest client.
 */
class PlaybackSyncReceiverTest {
    private lateinit var mockSocket: Socket
    private lateinit var sut: PlaybackSyncReceiver

    private val capturedListeners = mutableMapOf<String, Emitter.Listener>()

    private var playedAtMs: Long? = null
    private var pausedAtMs: Long? = null
    private var seekedToMs: Long? = null

    @Before
    fun setUp() {
        mockSocket = mockk(relaxed = true)

        // Capture socket.on(...) calls so tests can invoke listeners directly.
        val listenerSlot = slot<Emitter.Listener>()
        val eventSlot = slot<String>()
        io.mockk.every {
            mockSocket.on(capture(eventSlot), capture(listenerSlot))
        } answers {
            capturedListeners[eventSlot.captured] = listenerSlot.captured
            mockSocket
        }

        sut = PlaybackSyncReceiver(socket = mockSocket)
        sut.attachPlayer(
            onPlay = { playedAtMs = it },
            onPause = { pausedAtMs = it },
            onSeek = { seekedToMs = it },
        )
        sut.startListening()
    }

    // ── PLAY ─────────────────────────────────────────────────────────────────

    @Test
    fun `PLAY event invokes onPlay callback with positionMs`() {
        val payload = JSONObject().put("positionMs", 5_000L)
        capturedListeners[SocketEvents.PLAY]!!.call(payload)
        assertEquals(5_000L, playedAtMs)
    }

    @Test
    fun `PLAY event with zero position invokes onPlay with 0`() {
        val payload = JSONObject().put("positionMs", 0L)
        capturedListeners[SocketEvents.PLAY]!!.call(payload)
        assertEquals(0L, playedAtMs)
    }

    // ── PAUSE ─────────────────────────────────────────────────────────────────

    @Test
    fun `PAUSE event invokes onPause callback with positionMs`() {
        val payload = JSONObject().put("positionMs", 12_000L)
        capturedListeners[SocketEvents.PAUSE]!!.call(payload)
        assertEquals(12_000L, pausedAtMs)
    }

    // ── SEEK ─────────────────────────────────────────────────────────────────

    @Test
    fun `SEEK event invokes onSeek callback with positionMs`() {
        val payload = JSONObject().put("positionMs", 30_000L)
        capturedListeners[SocketEvents.SEEK]!!.call(payload)
        assertEquals(30_000L, seekedToMs)
    }

    // ── startListening / stopListening ────────────────────────────────────────

    @Test
    fun `startListening registers listeners for PLAY, PAUSE, and SEEK`() {
        verify { mockSocket.on(eq(SocketEvents.PLAY), any()) }
        verify { mockSocket.on(eq(SocketEvents.PAUSE), any()) }
        verify { mockSocket.on(eq(SocketEvents.SEEK), any()) }
    }

    @Test
    fun `stopListening removes all listeners`() {
        sut.stopListening()
        verify { mockSocket.off(SocketEvents.PLAY) }
        verify { mockSocket.off(SocketEvents.PAUSE) }
        verify { mockSocket.off(SocketEvents.SEEK) }
    }

    @Test
    fun `startListening is idempotent (removes old listeners before re-registering)`() {
        // Second call should not add duplicate listeners.
        sut.startListening()
        verify(atLeast = 2) { mockSocket.off(SocketEvents.PLAY) }
        verify(atLeast = 2) { mockSocket.off(SocketEvents.PAUSE) }
        verify(atLeast = 2) { mockSocket.off(SocketEvents.SEEK) }
    }

    // ── null / invalid payloads ───────────────────────────────────────────────

    @Test
    fun `PLAY event with non-JSONObject payload is silently ignored`() {
        capturedListeners[SocketEvents.PLAY]!!.call("invalid")
        assertEquals(null, playedAtMs)
    }

    @Test
    fun `PAUSE event with null args is silently ignored`() {
        capturedListeners[SocketEvents.PAUSE]!!.call(null)
        assertEquals(null, pausedAtMs)
    }
}
