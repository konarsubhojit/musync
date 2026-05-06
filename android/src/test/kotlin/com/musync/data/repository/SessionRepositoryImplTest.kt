package com.musync.data.repository

import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
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

    /** Stores the PARTICIPANTS_UPDATED listener so tests can simulate the server event. */
    private var participantsUpdatedListener: Emitter.Listener? = null

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
        // Capture the ROOM_CLOSED listener so tests can simulate the server event.
        val roomClosedSlot = slot<Emitter.Listener>()
        every { socket.on("ROOM_CLOSED", capture(roomClosedSlot)) } answers {
            roomClosedListener = roomClosedSlot.captured
            socket
        }
        // Capture the PARTICIPANTS_UPDATED listener so tests can simulate the server event.
        val participantsSlot = slot<Emitter.Listener>()
        every { socket.on("PARTICIPANTS_UPDATED", capture(participantsSlot)) } answers {
            participantsUpdatedListener = participantsSlot.captured
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
    // PARTICIPANTS_UPDATED server event emits SyncEvent.ParticipantsUpdated
    // ------------------------------------------------------------------

    @Test
    fun `emits ParticipantsUpdated when PARTICIPANTS_UPDATED server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val payload =
                JSONObject().apply {
                    put(
                        "participants",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("socketId", "s1")
                                    put("displayName", "Alice")
                                },
                            )
                            put(
                                JSONObject().apply {
                                    put("socketId", "s2")
                                    put("displayName", "Bob")
                                },
                            )
                        },
                    )
                }

            val emitted =
                collectEvents {
                    participantsUpdatedListener?.call(payload)
                }

            assertEquals(1, emitted.size)
            val event = emitted.first() as SyncEvent.ParticipantsUpdated
            assertEquals(2, event.participants.size)
            assertEquals("Alice", event.participants[0].displayName)
            assertEquals("s1", event.participants[0].socketId)
            assertEquals("Bob", event.participants[1].displayName)
        }

    @Test
    fun `PARTICIPANTS_UPDATED with missing socketId is ignored`() =
        runTest {
            repository.joinSession(guestSession)

            val payload =
                JSONObject().apply {
                    put(
                        "participants",
                        JSONArray().apply {
                            // No socketId field — should be filtered out
                            put(JSONObject().apply { put("displayName", "Alice") })
                        },
                    )
                }

            val emitted =
                collectEvents {
                    participantsUpdatedListener?.call(payload)
                }

            val event = emitted.firstOrNull() as? SyncEvent.ParticipantsUpdated
            assertTrue("Event should be emitted but with empty list", event != null && event.participants.isEmpty())
        }
}
