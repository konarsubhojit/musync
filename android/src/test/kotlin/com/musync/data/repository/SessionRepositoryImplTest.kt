package com.musync.data.repository

import com.musync.data.model.ChatMessage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryImplTest {
    private lateinit var repository: SessionRepositoryImpl
    private lateinit var socket: Socket

    /** Stores the ROOM_CLOSED listener registered by the repository so tests can invoke it. */
    private var roomClosedListener: Emitter.Listener? = null

    /** Stores the CHAT_MESSAGE listener so tests can simulate server events. */
    private var chatMessageListener: Emitter.Listener? = null

    /** Stores the REACTION listener so tests can simulate server events. */
    private var reactionListener: Emitter.Listener? = null

    /** Stores the TYPING listener so tests can simulate server events. */
    private var typingListener: Emitter.Listener? = null

    /** Stores the peer_joined listener registered by the repository so tests can invoke it. */
    private var peerJoinedListener: Emitter.Listener? = null

    /** Stores the peer_left listener registered by the repository so tests can invoke it. */
    private var peerLeftListener: Emitter.Listener? = null

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
        // Capture socket listeners so tests can simulate server events.
        val roomClosedSlot = slot<Emitter.Listener>()
        every { socket.on("ROOM_CLOSED", capture(roomClosedSlot)) } answers {
            roomClosedListener = roomClosedSlot.captured
            socket
        }
        // Capture CHAT_MESSAGE listener.
        val chatSlot = slot<Emitter.Listener>()
        every { socket.on("CHAT_MESSAGE", capture(chatSlot)) } answers {
            chatMessageListener = chatSlot.captured
            socket
        }
        // Capture REACTION listener.
        val reactionSlot = slot<Emitter.Listener>()
        every { socket.on("REACTION", capture(reactionSlot)) } answers {
            reactionListener = reactionSlot.captured
            socket
        }
        // Capture TYPING listener.
        val typingSlot = slot<Emitter.Listener>()
        every { socket.on("TYPING", capture(typingSlot)) } answers {
            typingListener = typingSlot.captured
            socket
        }
        val peerJoinedSlot = slot<Emitter.Listener>()
        every { socket.on("peer_joined", capture(peerJoinedSlot)) } answers {
            peerJoinedListener = peerJoinedSlot.captured
            socket
        }
        val peerLeftSlot = slot<Emitter.Listener>()
        every { socket.on("peer_left", capture(peerLeftSlot)) } answers {
            peerLeftListener = peerLeftSlot.captured
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
    // Helper: collects ChatMessages emitted during [block], then cancels.
    // ------------------------------------------------------------------

    private fun TestScope.collectChatMessages(block: () -> Unit): List<ChatMessage> {
        val received = mutableListOf<ChatMessage>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.chatMessages.collect { received.add(it) }
            }
        block()
        job.cancel()
        return received
    }

    // ------------------------------------------------------------------
    // Helper: collects reactions emitted during [block], then cancels.
    // ------------------------------------------------------------------

    private fun TestScope.collectReactions(block: () -> Unit): List<String> {
        val received = mutableListOf<String>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.reactions.collect { received.add(it) }
            }
        block()
        job.cancel()
        return received
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
    // CHAT_MESSAGE: incoming messages are emitted via chatMessages flow
    // ------------------------------------------------------------------

    @Test
    fun `emits ChatMessage when CHAT_MESSAGE server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val received =
                collectChatMessages {
                    val payload =
                        JSONObject()
                            .put("senderId", "user-1")
                            .put("senderName", "Alice")
                            .put("text", "Hello!")
                    chatMessageListener?.call(payload)
                }

            assertEquals(1, received.size)
            with(received.first()) {
                assertEquals("user-1", senderId)
                assertEquals("Alice", senderName)
                assertEquals("Hello!", text)
                assertFalse("Incoming message should not be local", isLocal)
            }
        }

    @Test
    fun `ignores CHAT_MESSAGE event with missing senderId`() =
        runTest {
            repository.joinSession(guestSession)

            val received =
                collectChatMessages {
                    val payload = JSONObject().put("text", "Hello!")
                    chatMessageListener?.call(payload)
                }

            assertTrue("No message expected when senderId is missing", received.isEmpty())
        }

    @Test
    fun `ignores CHAT_MESSAGE event with blank text`() =
        runTest {
            repository.joinSession(guestSession)

            val received =
                collectChatMessages {
                    val payload =
                        JSONObject()
                            .put("senderId", "user-1")
                            .put("senderName", "Alice")
                            .put("text", "   ")
                    chatMessageListener?.call(payload)
                }

            assertTrue("No message expected when text is blank", received.isEmpty())
        }

    // ------------------------------------------------------------------
    // sendChatMessage: local echo + socket emission
    // ------------------------------------------------------------------

    @Test
    fun `sendChatMessage emits local ChatMessage and calls socket emit`() =
        runTest {
            repository.joinSession(hostSession)

            val received =
                collectChatMessages {
                    repository.sendChatMessage("Hi there", "Bob")
                }

            assertEquals(1, received.size)
            with(received.first()) {
                assertEquals("Hi there", text)
                assertEquals("Bob", senderName)
                assertTrue("Sent message should be local", isLocal)
            }
            verify {
                socket.emit("CHAT_MESSAGE", any<JSONObject>())
            }
        }

    @Test
    fun `sendChatMessage is a no-op when there is no active session`() =
        runTest {
            val received =
                collectChatMessages {
                    repository.sendChatMessage("Hi", "Bob")
                }

            assertTrue("No message when no session", received.isEmpty())
            verify(exactly = 0) { socket.emit("CHAT_MESSAGE", any<JSONObject>()) }
        }

    @Test
    fun `sendChatMessage is a no-op when text is blank`() =
        runTest {
            repository.joinSession(hostSession)

            val received =
                collectChatMessages {
                    repository.sendChatMessage("   ", "Bob")
                }

            assertTrue("No message when text is blank", received.isEmpty())
            verify(exactly = 0) { socket.emit("CHAT_MESSAGE", any<JSONObject>()) }
        }

    // ------------------------------------------------------------------
    // REACTION: incoming reactions are emitted via reactions flow
    // ------------------------------------------------------------------

    @Test
    fun `emits reaction emoji when REACTION server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val received =
                collectReactions {
                    val payload = JSONObject().put("emoji", "🔥")
                    reactionListener?.call(payload)
                }

            assertEquals(listOf("🔥"), received)
        }

    @Test
    fun `ignores REACTION event with blank emoji`() =
        runTest {
            repository.joinSession(guestSession)

            val received =
                collectReactions {
                    val payload = JSONObject().put("emoji", "  ")
                    reactionListener?.call(payload)
                }

            assertTrue("No reaction expected when emoji is blank", received.isEmpty())
        }

    // ------------------------------------------------------------------
    // sendReaction: delegates to socket
    // ------------------------------------------------------------------

    @Test
    fun `sendReaction calls socket emit with correct payload`() =
        runTest {
            repository.joinSession(hostSession)
            repository.sendReaction("❤️")

            verify { socket.emit("REACTION", any<JSONObject>()) }
        }

    @Test
    fun `sendReaction is a no-op when there is no active session`() =
        runTest {
            repository.sendReaction("❤️")

            verify(exactly = 0) { socket.emit("REACTION", any<JSONObject>()) }
        }

    // ------------------------------------------------------------------
    // TYPING: incoming typing indicators update typingUsers state
    // ------------------------------------------------------------------

    @Test
    fun `adds senderId to typingUsers when TYPING event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val payload = JSONObject().put("senderId", "user-1").put("senderName", "Alice")
            typingListener?.call(payload)

            assertTrue("user-1 should be in typingUsers", repository.typingUsers.value.contains("user-1"))
        }

    @Test
    fun `ignores TYPING event with blank senderId`() =
        runTest {
            repository.joinSession(guestSession)

            val payload = JSONObject().put("senderId", "").put("senderName", "Alice")
            typingListener?.call(payload)

            assertTrue("typingUsers should be empty when senderId is blank", repository.typingUsers.value.isEmpty())
        }

    // ------------------------------------------------------------------
    // sendTyping: delegates to socket
    // ------------------------------------------------------------------

    @Test
    fun `sendTyping calls socket emit with correct event`() =
        runTest {
            repository.joinSession(hostSession)
            repository.sendTyping("Bob")

            verify { socket.emit("TYPING", any<JSONObject>()) }
        }

    @Test
    fun `sendTyping is a no-op when there is no active session`() =
        runTest {
            repository.sendTyping("Bob")

            verify(exactly = 0) { socket.emit("TYPING", any<JSONObject>()) }
        }

    // ------------------------------------------------------------------
    // PEER_JOINED server event emits SyncEvent.PeerJoined
    // ------------------------------------------------------------------

    @Test
    fun `emits PeerJoined when peer_joined server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    peerJoinedListener?.call()
                }

            assertTrue("Expected at least one PeerJoined event", emitted.any { it is SyncEvent.PeerJoined })
        }

    // ------------------------------------------------------------------
    // PEER_LEFT server event emits SyncEvent.PeerLeft
    // ------------------------------------------------------------------

    @Test
    fun `emits PeerLeft when peer_left server event is received`() =
        runTest {
            repository.joinSession(guestSession)

            val emitted =
                collectEvents {
                    peerLeftListener?.call()
                }

            assertTrue("Expected at least one PeerLeft event", emitted.any { it is SyncEvent.PeerLeft })
        }
}
