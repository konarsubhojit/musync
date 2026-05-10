package com.musync.data.repository

import com.musync.data.model.ChatMessage
import com.musync.data.model.Participant
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
import org.json.JSONArray
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

    private var roomClosedListener: Emitter.Listener? = null
    private var hostTransferredListener: Emitter.Listener? = null
    private var democraticModeChangedListener: Emitter.Listener? = null
    private var autoApproveQueueChangedListener: Emitter.Listener? = null
    private var queueAddRequestListener: Emitter.Listener? = null
    private var chatMessageListener: Emitter.Listener? = null
    private var reactionListener: Emitter.Listener? = null
    private var typingListener: Emitter.Listener? = null
    private var peerJoinedListener: Emitter.Listener? = null
    private var peerLeftListener: Emitter.Listener? = null
    private var participantsUpdatedListener: Emitter.Listener? = null
    private var connectListener: Emitter.Listener? = null
    private var disconnectListener: Emitter.Listener? = null
    private var connectErrorListener: Emitter.Listener? = null
    private var reconnectAttemptListener: Emitter.Listener? = null
    private var reconnectErrorListener: Emitter.Listener? = null
    private var reconnectFailedListener: Emitter.Listener? = null

    private val hostSession = Session(sessionId = "session-1", hostId = "user-1", localUserId = "user-1")
    private val guestSession = Session(sessionId = "session-1", hostId = "user-1", localUserId = "user-2")

    @Before
    fun setUp() {
        socket = mockk(relaxed = true)
        every { socket.id() } returns "my-socket-id"

        val roomClosedSlot = slot<Emitter.Listener>()
        every { socket.on("ROOM_CLOSED", capture(roomClosedSlot)) } answers {
            roomClosedListener = roomClosedSlot.captured
            socket
        }
        val hostTransferredSlot = slot<Emitter.Listener>()
        every { socket.on("HOST_TRANSFERRED", capture(hostTransferredSlot)) } answers {
            hostTransferredListener = hostTransferredSlot.captured
            socket
        }
        val democraticSlot = slot<Emitter.Listener>()
        every { socket.on("DEMOCRATIC_MODE_CHANGED", capture(democraticSlot)) } answers {
            democraticModeChangedListener = democraticSlot.captured
            socket
        }
        val autoApproveSlot = slot<Emitter.Listener>()
        every { socket.on("AUTO_APPROVE_QUEUE_CHANGED", capture(autoApproveSlot)) } answers {
            autoApproveQueueChangedListener = autoApproveSlot.captured
            socket
        }
        val queueAddRequestSlot = slot<Emitter.Listener>()
        every { socket.on("QUEUE_ADD_REQUEST", capture(queueAddRequestSlot)) } answers {
            queueAddRequestListener = queueAddRequestSlot.captured
            socket
        }
        val chatSlot = slot<Emitter.Listener>()
        every { socket.on("CHAT_MESSAGE", capture(chatSlot)) } answers {
            chatMessageListener = chatSlot.captured
            socket
        }
        val reactionSlot = slot<Emitter.Listener>()
        every { socket.on("REACTION", capture(reactionSlot)) } answers {
            reactionListener = reactionSlot.captured
            socket
        }
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
        val participantsUpdatedSlot = slot<Emitter.Listener>()
        every { socket.on("PARTICIPANTS_UPDATED", capture(participantsUpdatedSlot)) } answers {
            participantsUpdatedListener = participantsUpdatedSlot.captured
            socket
        }
        val connectSlot = slot<Emitter.Listener>()
        every { socket.on(Socket.EVENT_CONNECT, capture(connectSlot)) } answers {
            connectListener = connectSlot.captured
            socket
        }
        val disconnectSlot = slot<Emitter.Listener>()
        every { socket.on(Socket.EVENT_DISCONNECT, capture(disconnectSlot)) } answers {
            disconnectListener = disconnectSlot.captured
            socket
        }
        val connectErrorSlot = slot<Emitter.Listener>()
        every { socket.on(Socket.EVENT_CONNECT_ERROR, capture(connectErrorSlot)) } answers {
            connectErrorListener = connectErrorSlot.captured
            socket
        }
        val reconnectAttemptSlot = slot<Emitter.Listener>()
        every { socket.on("reconnect_attempt", capture(reconnectAttemptSlot)) } answers {
            reconnectAttemptListener = reconnectAttemptSlot.captured
            socket
        }
        val reconnectErrorSlot = slot<Emitter.Listener>()
        every { socket.on("reconnect_error", capture(reconnectErrorSlot)) } answers {
            reconnectErrorListener = reconnectErrorSlot.captured
            socket
        }
        val reconnectFailedSlot = slot<Emitter.Listener>()
        every { socket.on("reconnect_failed", capture(reconnectFailedSlot)) } answers {
            reconnectFailedListener = reconnectFailedSlot.captured
            socket
        }
        repository = SessionRepositoryImpl(socket)
    }

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
            val emitted = collectEvents { repository.onPlayerStateChanged(PlayerState.ENDED) }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.PlayNext("session-1"), emitted.first())
        }

    @Test
    fun `does not emit PlayNext when guest player reaches ENDED state`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted = collectEvents { repository.onPlayerStateChanged(PlayerState.ENDED) }
            assertTrue("Guest should not emit PLAY_NEXT", emitted.isEmpty())
        }

    @Test
    fun `does not emit PlayNext when there is no active session`() =
        runTest {
            val emitted = collectEvents { repository.onPlayerStateChanged(PlayerState.ENDED) }
            assertTrue("No session should produce no events", emitted.isEmpty())
        }

    @Test
    fun `emits PlayNext only once even when ENDED fires multiple times`() =
        runTest {
            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }
            assertEquals("PLAY_NEXT must be emitted exactly once", 1, emitted.size)
        }

    @Test
    fun `resets guard on PLAYING so next track can emit PlayNext again`() =
        runTest {
            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.onPlayerStateChanged(PlayerState.PLAYING)
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }
            assertEquals("PLAY_NEXT should be emitted once per track", 2, emitted.size)
        }

    @Test
    fun `resets guard when joining a new session`() =
        runTest {
            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                    repository.joinSession(hostSession.copy(sessionId = "session-2"))
                    repository.onPlayerStateChanged(PlayerState.ENDED)
                }
            val playNext = emitted.filterIsInstance<SyncEvent.PlayNext>()
            assertEquals(2, playNext.size)
            assertEquals(SyncEvent.PlayNext("session-1"), playNext[0])
            assertEquals(SyncEvent.PlayNext("session-2"), playNext[1])
        }

    @Test
    fun `does not emit PlayNext after leaving session`() =
        runTest {
            repository.joinSession(hostSession)
            repository.leaveSession()
            val emitted = collectEvents { repository.onPlayerStateChanged(PlayerState.ENDED) }
            assertTrue("No events expected after leaving session", emitted.isEmpty())
        }

    // ------------------------------------------------------------------
    // ROOM_CLOSED
    // ------------------------------------------------------------------

    @Test
    fun `emits RoomClosed and clears session when ROOM_CLOSED server event is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted = collectEvents { roomClosedListener?.call() }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.RoomClosed, emitted.first())
            assertTrue("Session should be cleared after ROOM_CLOSED", repository.session.value == null)
        }

    // ------------------------------------------------------------------
    // HOST_TRANSFERRED
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

    @Test
    fun `transferHost emits TRANSFER_HOST socket event with correct payload`() =
        runTest {
            repository.transferHost("session-1", "new-host-socket-id")
            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("TRANSFER_HOST", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            assertEquals("new-host-socket-id", payloadSlot.captured.getString("newHostSocketId"))
        }

    // ------------------------------------------------------------------
    // DEMOCRATIC_MODE_CHANGED
    // ------------------------------------------------------------------

    @Test
    fun `emits DemocraticModeChanged(true) when DEMOCRATIC_MODE_CHANGED enabled=true is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted =
                collectEvents {
                    val payload = JSONObject().apply { put("enabled", true) }
                    democraticModeChangedListener?.call(payload)
                }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.DemocraticModeChanged(enabled = true), emitted.first())
        }

    @Test
    fun `emits DemocraticModeChanged(false) when DEMOCRATIC_MODE_CHANGED enabled=false is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted =
                collectEvents {
                    val payload = JSONObject().apply { put("enabled", false) }
                    democraticModeChangedListener?.call(payload)
                }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.DemocraticModeChanged(enabled = false), emitted.first())
        }

    @Test
    fun `setDemocraticMode emits SET_DEMOCRATIC_MODE socket event`() =
        runTest {
            repository.setDemocraticMode("session-1", true)
            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("SET_DEMOCRATIC_MODE", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            assertTrue(payloadSlot.captured.getBoolean("enabled"))
        }

    // ------------------------------------------------------------------
    // AUTO_APPROVE_QUEUE_CHANGED
    // ------------------------------------------------------------------

    @Test
    fun `emits AutoApproveQueueChanged when AUTO_APPROVE_QUEUE_CHANGED is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted =
                collectEvents {
                    val payload = JSONObject().apply { put("enabled", false) }
                    autoApproveQueueChangedListener?.call(payload)
                }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.AutoApproveQueueChanged(enabled = false), emitted.first())
        }

    @Test
    fun `setAutoApproveQueue emits SET_AUTO_APPROVE_QUEUE socket event`() =
        runTest {
            repository.setAutoApproveQueue("session-1", false)
            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("SET_AUTO_APPROVE_QUEUE", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            assertFalse(payloadSlot.captured.getBoolean("enabled"))
        }

    // ------------------------------------------------------------------
    // QUEUE_ADD_REQUEST
    // ------------------------------------------------------------------

    @Test
    fun `emits QueueAddRequest when QUEUE_ADD_REQUEST is received`() =
        runTest {
            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    val payload =
                        JSONObject().apply {
                            put("id", "track-123")
                            put("title", "My Song")
                        }
                    queueAddRequestListener?.call(payload)
                }
            assertEquals(1, emitted.size)
            assertEquals(SyncEvent.QueueAddRequest("track-123", "My Song"), emitted.first())
        }

    @Test
    fun `requestQueueAdd emits REQUEST_QUEUE_ADD socket event with track payload`() =
        runTest {
            repository.requestQueueAdd("session-1", "track-abc", "Awesome Track")
            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("REQUEST_QUEUE_ADD", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            val track = payloadSlot.captured.getJSONObject("track")
            assertEquals("track-abc", track.getString("id"))
            assertEquals("Awesome Track", track.getString("title"))
        }

    @Test
    fun `approveQueueAdd emits APPROVE_QUEUE_ADD socket event with track payload`() =
        runTest {
            repository.approveQueueAdd("session-1", "track-abc", "Awesome Track")
            val payloadSlot = slot<JSONObject>()
            verify { socket.emit("APPROVE_QUEUE_ADD", capture(payloadSlot)) }
            assertEquals("session-1", payloadSlot.captured.getString("roomId"))
            val track = payloadSlot.captured.getJSONObject("track")
            assertEquals("track-abc", track.getString("id"))
            assertEquals("Awesome Track", track.getString("title"))
        }

    // ------------------------------------------------------------------
    // CHAT_MESSAGE
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

    @Test
    fun `sendChatMessage emits local ChatMessage and calls socket emit`() =
        runTest {
            repository.joinSession(hostSession)
            val received = collectChatMessages { repository.sendChatMessage("Hi there", "Bob") }
            assertEquals(1, received.size)
            with(received.first()) {
                assertEquals("Hi there", text)
                assertEquals("Bob", senderName)
                assertTrue("Sent message should be local", isLocal)
            }
            verify { socket.emit("CHAT_MESSAGE", any<JSONObject>()) }
        }

    @Test
    fun `sendChatMessage is a no-op when there is no active session`() =
        runTest {
            val received = collectChatMessages { repository.sendChatMessage("Hi", "Bob") }
            assertTrue("No message when no session", received.isEmpty())
            verify(exactly = 0) { socket.emit("CHAT_MESSAGE", any<JSONObject>()) }
        }

    @Test
    fun `sendChatMessage is a no-op when text is blank`() =
        runTest {
            repository.joinSession(hostSession)
            val received = collectChatMessages { repository.sendChatMessage("   ", "Bob") }
            assertTrue("No message when text is blank", received.isEmpty())
            verify(exactly = 0) { socket.emit("CHAT_MESSAGE", any<JSONObject>()) }
        }

    // ------------------------------------------------------------------
    // REACTION
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
    // TYPING
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
    // PEER_JOINED / PEER_LEFT
    // ------------------------------------------------------------------

    @Test
    fun `emits PeerJoined when peer_joined server event is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted = collectEvents { peerJoinedListener?.call() }
            assertTrue("Expected at least one PeerJoined event", emitted.any { it is SyncEvent.PeerJoined })
        }

    @Test
    fun `emits PeerLeft when peer_left server event is received`() =
        runTest {
            repository.joinSession(guestSession)
            val emitted = collectEvents { peerLeftListener?.call() }
            assertTrue("Expected at least one PeerLeft event", emitted.any { it is SyncEvent.PeerLeft })
        }

    // ------------------------------------------------------------------
    // PARTICIPANTS_UPDATED
    // ------------------------------------------------------------------

    @Test
    fun `emits ParticipantsUpdated when PARTICIPANTS_UPDATED event is received`() =
        runTest {
            repository.joinSession(hostSession)
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
            val emitted = collectEvents { participantsUpdatedListener?.call(payload) }
            val event = emitted.filterIsInstance<SyncEvent.ParticipantsUpdated>().firstOrNull()
            assertFalse("Expected a ParticipantsUpdated event", event == null)
            assertEquals(
                listOf(
                    Participant(socketId = "s1", displayName = "Alice"),
                    Participant(socketId = "s2", displayName = "Bob"),
                ),
                event!!.participants,
            )
        }

    @Test
    fun `joinSession emits JOIN_ROOM with displayName in JSONObject payload`() =
        runTest {
            val session = hostSession.copy(displayName = "Alice")
            repository.joinSession(session)
            verify {
                socket.emit(
                    eq("join_room"),
                    match<JSONObject> { obj ->
                        obj.optString("roomId") == session.sessionId &&
                            obj.optString("displayName") == "Alice"
                    },
                    any(),
                )
            }
        }

    @Test
    fun `emits ConnectionStateChanged for connect and disconnect socket events`() =
        runTest {
            val emitted =
                collectEvents {
                    connectListener?.call()
                    disconnectListener?.call("transport close")
                    reconnectAttemptListener?.call(1)
                    reconnectErrorListener?.call(RuntimeException("retry failed"))
                    connectErrorListener?.call(RuntimeException("network"))
                    reconnectFailedListener?.call()
                }
            val states = emitted.filterIsInstance<SyncEvent.ConnectionStateChanged>().map { it.state }
            assertTrue(states.contains(com.musync.data.model.ConnectionState.CONNECTED))
            assertTrue(states.contains(com.musync.data.model.ConnectionState.CONNECTING))
            assertTrue(states.contains(com.musync.data.model.ConnectionState.DISCONNECTED))
        }

    @Test
    fun `joinSession emits RoomJoinFailed when ack returns error`() =
        runTest {
            val ackSlot = slot<io.socket.client.Ack>()
            every { socket.emit(eq("join_room"), any<JSONObject>(), capture(ackSlot)) } returns socket

            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    ackSlot.captured.call(JSONObject().put("error", "invalid roomId"))
                }
            assertTrue(emitted.any { it is SyncEvent.RoomJoinFailed })
        }

    @Test
    fun `joinSession emits RoomJoinFailed when ack payload is missing`() =
        runTest {
            val ackSlot = slot<io.socket.client.Ack>()
            every { socket.emit(eq("join_room"), any<JSONObject>(), capture(ackSlot)) } returns socket

            repository.joinSession(hostSession)
            val emitted =
                collectEvents {
                    ackSlot.captured.call()
                }
            assertTrue(emitted.any { it is SyncEvent.RoomJoinFailed })
        }
}
