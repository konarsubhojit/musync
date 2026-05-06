package com.musync.data.repository

import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryImplTest {
    private lateinit var repository: SessionRepositoryImpl
    private lateinit var socket: Socket

    /** Stores the ROOM_CLOSED listener registered by the repository so tests can invoke it. */
    private var roomClosedListener: Emitter.Listener? = null

    /** Stores the HOST_TRANSFERRED listener registered by the repository so tests can invoke it. */
    private var hostTransferredListener: Emitter.Listener? = null

    private val hostSession =
        Session(
            sessionId = "session-1",
            hostId = "user-1",
            localUserId = "user-1",
        )

    private val guestSession =
        Session(
            sessionId = "session-1",
            hostId = "user-1",
            localUserId = "user-2",
        )

    @Before
    fun setUp() {
        socket = mockk(relaxed = true)
        every { socket.id() } returns "my-socket-id"

        // Capture the ROOM_CLOSED listener so tests can simulate the server event.
        val roomClosedSlot = slot<Emitter.Listener>()
        every { socket.on("ROOM_CLOSED", capture(roomClosedSlot)) } answers {
            roomClosedListener = roomClosedSlot.captured
            socket
        }
        // Capture the HOST_TRANSFERRED listener so tests can simulate the server event.
        val hostTransferredSlot = slot<Emitter.Listener>()
        every { socket.on("HOST_TRANSFERRED", capture(hostTransferredSlot)) } answers {
            hostTransferredListener = hostTransferredSlot.captured
            socket
        }
        repository = SessionRepositoryImpl(socket)
    }

    // ------------------------------------------------------------------
    // Helper: collects SyncEvents emitted during [block], then cancels.
    // ------------------------------------------------------------------

    private fun TestScope.collectEvents(block: () -> Unit): List<SyncEvent> {
        val emitted = mutableListOf<SyncEvent>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.events.collect { emitted.add(it) }
            }
        block()
        job.cancel()
        return emitted
    }

    // ------------------------------------------------------------------
    // Host emits PLAY_NEXT on ENDED
    // ------------------------------------------------------------------

    @Test
    fun `emits PlayNext when host player reaches ENDED state`() =
        runTest {
            repository.joinSession(hostSession)

            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.PlayNext("session-1"), emitted.first())
        }

    // ------------------------------------------------------------------
    // Guest must NOT emit PLAY_NEXT
    // ------------------------------------------------------------------

    @Test
    fun `does not emit PlayNext when guest player reaches ENDED state`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertTrue("Guest should not emit PLAY_NEXT", emitted.isEmpty())
        }

    // ------------------------------------------------------------------
    // No session → no event
    // ------------------------------------------------------------------

    @Test
    fun `does not emit PlayNext when there is no active session`() =
        runTest {
            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertTrue("No session should produce no events", emitted.isEmpty())
        }

    // ------------------------------------------------------------------
    // Race condition: duplicate ENDED callbacks emit only one PLAY_NEXT
    // ------------------------------------------------------------------

    @Test
    fun `emits PlayNext only once even when ENDED fires multiple times`() =
        runTest {
            repository.joinSession(hostSession)

            val emitted =
                collectEvents {
                    // Simulate duplicate ENDED callbacks (e.g. from the YouTube player)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertEquals("PLAY_NEXT must be emitted exactly once", 1, emitted.size)
        }

    // ------------------------------------------------------------------
    // Guard resets when PLAYING starts (new track)
    // ------------------------------------------------------------------

    @Test
    fun `resets guard on PLAYING so next track can emit PlayNext again`() =
        runTest {
            repository.joinSession(hostSession)

            val emitted =
                collectEvents {
                    // First track ends
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    // New track starts
                    repository.onPlayerStateChanged(PlayerState.PLAYING)
                    // Second track ends
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertEquals("PLAY_NEXT should be emitted once per track", 2, emitted.size)
        }

    // ------------------------------------------------------------------
    // Guard resets when joining a new session
    // ------------------------------------------------------------------

    @Test
    fun `resets guard when joining a new session`() =
        runTest {
            repository.joinSession(hostSession)

            val emitted =
                collectEvents {
                    // Track ends in first session
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    // Re-join (e.g. reconnect to same or different session)
                    repository.joinSession(hostSession.copy(sessionId = "session-2"))
                    // Track ends in the new session
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertEquals(2, emitted.size)
            assertEquals(SyncEvent.PlayNext("session-1"), emitted[0])
            assertEquals(SyncEvent.PlayNext("session-2"), emitted[1])
        }

    // ------------------------------------------------------------------
    // After leaving a session, ENDED produces no event
    // ------------------------------------------------------------------

    @Test
    fun `does not emit PlayNext after leaving session`() =
        runTest {
            repository.joinSession(hostSession)
            repository.leaveSession()

            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }

            assertTrue("No events expected after leaving session", emitted.isEmpty())
        }

    // ------------------------------------------------------------------
    // ROOM_CLOSED server event emits SyncEvent.RoomClosed
    // ------------------------------------------------------------------

    @Test
    fun `emits RoomClosed and clears session when ROOM_CLOSED server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    // Simulate the server broadcasting ROOM_CLOSED
                    roomClosedListener?.call()
                }

            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.RoomClosed, emitted.first())
            assertTrue("Session should be cleared after ROOM_CLOSED", repository.session.value == null)
        }

    // ------------------------------------------------------------------
    // HOST_TRANSFERRED: local user is the new host
    // ------------------------------------------------------------------

    @Test
    fun `emits HostTransferred(isNowHost=true) when socket id matches newHostSocketId`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    val payload = JSONObject().apply { put("newHostSocketId", "my-socket-id") }
                    hostTransferredListener?.call(payload)
                }

            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.HostTransferred(isNowHost = true), emitted.first())
        }

    // ------------------------------------------------------------------
    // HOST_TRANSFERRED: local user is NOT the new host
    // ------------------------------------------------------------------

    @Test
    fun `emits HostTransferred(isNowHost=false) when socket id does not match newHostSocketId`() =
        runTest {
            repository.joinSession(hostSession)

            val emitted =
                collectEvents {
                    val payload = JSONObject().apply { put("newHostSocketId", "other-socket-id") }
                    hostTransferredListener?.call(payload)
                }

            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.HostTransferred(isNowHost = false), emitted.first())
        }

    // ------------------------------------------------------------------
    // HOST_TRANSFERRED: missing payload is a no-op
    // ------------------------------------------------------------------

    @Test
    fun `does not emit HostTransferred when HOST_TRANSFERRED payload is missing newHostSocketId`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    val emptyPayload = JSONObject()
                    hostTransferredListener?.call(emptyPayload)
                }

            assertTrue("Empty payload should produce no events", emitted.isEmpty())
        }

    // ------------------------------------------------------------------
    // transferHost emits TRANSFER_HOST socket event
    // ------------------------------------------------------------------

    @Test
    fun `transferHost emits TRANSFER_HOST socket event with correct payload`() =
        runTest {
            repository.transferHost("session-1", "new-host-socket-id")

            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("TRANSFER_HOST", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            assertEquals("new-host-socket-id", payloadSlot.captured.getString("newHostSocketId"))
        }
}
