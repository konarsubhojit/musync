package com.musync.data.repository

import com.musync.data.model.ChatMessage
import com.musync.data.model.ConnectionState
import com.musync.data.model.Participant
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.sync.SocketEvents
import io.socket.client.Ack
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl
    @Inject
    constructor(
        private val socket: Socket,
    ) : SessionRepository {
        companion object {
            private const val EVENT_BUFFER_CAPACITY = 8
            private const val CHAT_BUFFER_CAPACITY = 64
            private const val TYPING_TIMEOUT_MS = 3_000L
            private const val MAX_CHAT_MESSAGES = 200
        }

        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _session = MutableStateFlow<Session?>(null)
        private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
        private val _chatMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = CHAT_BUFFER_CAPACITY)
        private val _reactions = MutableSharedFlow<String>(extraBufferCapacity = CHAT_BUFFER_CAPACITY)
        private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())

        private val playNextEmitted = AtomicBoolean(false)
        private val typingJobsMutex = Mutex()
        private val typingJobs = mutableMapOf<String, Job>()

        init {
            socket.on(Socket.EVENT_CONNECT) {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.CONNECTED))
            }
            socket.on(Socket.EVENT_DISCONNECT) {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.DISCONNECTED))
            }
            socket.on(Socket.EVENT_CONNECT_ERROR) {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.CONNECTING))
            }
            socket.on("reconnect_attempt") {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.CONNECTING))
            }
            socket.on("reconnect_error") {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.CONNECTING))
            }
            socket.on("reconnect_failed") {
                _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.DISCONNECTED))
            }

            socket.on(SocketEvents.ROOM_CLOSED) {
                _session.value = null
                _events.tryEmit(SyncEvent.RoomClosed)
            }
            socket.on(SocketEvents.PEER_JOINED) {
                _events.tryEmit(SyncEvent.PeerJoined)
            }
            socket.on(SocketEvents.PEER_LEFT) {
                _events.tryEmit(SyncEvent.PeerLeft)
            }

            socket.on(SocketEvents.HOST_TRANSFERRED) { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                val newHostSocketId = payload.optString("newHostSocketId")
                if (newHostSocketId.isBlank()) return@on
                val isNowHost = newHostSocketId == socket.id()
                _events.tryEmit(SyncEvent.HostTransferred(isNowHost))
            }

            socket.on(SocketEvents.DEMOCRATIC_MODE_CHANGED) { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                val enabled = payload.optBoolean("enabled", false)
                _events.tryEmit(SyncEvent.DemocraticModeChanged(enabled))
            }

            socket.on(SocketEvents.AUTO_APPROVE_QUEUE_CHANGED) { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                val enabled = payload.optBoolean("enabled", true)
                _events.tryEmit(SyncEvent.AutoApproveQueueChanged(enabled))
            }

            socket.on(SocketEvents.QUEUE_ADD_REQUEST) { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                val trackId = payload.optString("id").takeIf { it.isNotBlank() } ?: return@on
                val trackTitle = payload.optString("title").takeIf { it.isNotBlank() } ?: return@on
                _events.tryEmit(SyncEvent.QueueAddRequest(trackId, trackTitle))
            }

            socket.on(SocketEvents.CHAT_MESSAGE) { args ->
                val obj = args?.firstOrNull() as? JSONObject ?: return@on
                val senderId = obj.optString("senderId").takeIf { it.isNotBlank() } ?: return@on
                val senderName = obj.optString("senderName").ifBlank { "Someone" }
                val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return@on
                val message =
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        senderId = senderId,
                        senderName = senderName,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isLocal = false,
                    )
                _chatMessages.tryEmit(message)
            }

            socket.on(SocketEvents.REACTION) { args ->
                val obj = args?.firstOrNull() as? JSONObject ?: return@on
                val emoji = obj.optString("emoji").takeIf { it.isNotBlank() } ?: return@on
                _reactions.tryEmit(emoji)
            }

            socket.on(SocketEvents.TYPING) { args ->
                val obj = args?.firstOrNull() as? JSONObject ?: return@on
                val senderId = obj.optString("senderId").takeIf { it.isNotBlank() } ?: return@on
                _typingUsers.update { it + senderId }
                scheduleTypingTimeout(senderId)
            }

            socket.on(SocketEvents.PARTICIPANTS_UPDATED) { args ->
                val payload = args?.getOrNull(0) as? JSONObject ?: return@on
                val participantsArray = payload.optJSONArray("participants") ?: return@on
                val participants =
                    (0 until participantsArray.length()).mapNotNull { i ->
                        val obj = participantsArray.optJSONObject(i) ?: return@mapNotNull null
                        val socketId = obj.optString("socketId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val displayName = obj.optString("displayName")
                        Participant(socketId = socketId, displayName = displayName)
                    }
                _events.tryEmit(SyncEvent.ParticipantsUpdated(participants))
            }
        }

        override val session: StateFlow<Session?> = _session.asStateFlow()
        override val events: SharedFlow<SyncEvent> = _events.asSharedFlow()
        override val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()
        override val reactions: SharedFlow<String> = _reactions.asSharedFlow()
        override val typingUsers: StateFlow<Set<String>> = _typingUsers.asStateFlow()

        override fun onPlayerStateChanged(state: PlayerState) {
            when (state) {
                PlayerState.ENDED -> handleTrackEnded()
                PlayerState.PLAYING -> playNextEmitted.set(false)
                else -> Unit
            }
        }

        override fun joinSession(session: Session) {
            playNextEmitted.set(false)
            _session.value = session
            _events.tryEmit(SyncEvent.ConnectionStateChanged(ConnectionState.CONNECTING))
            socket.connect()
            val payload =
                JSONObject().apply {
                    put("roomId", session.sessionId)
                    put("displayName", session.displayName)
                }
            socket.emit(
                SocketEvents.JOIN_ROOM,
                payload,
                Ack { args ->
                    val data = (args.getOrNull(0) as? JSONObject)
                    if (data == null) {
                        _events.tryEmit(SyncEvent.RoomJoinFailed("No response from server"))
                        return@Ack
                    }
                    val error = data.optString("error").takeIf { it.isNotBlank() }
                    val hasOk = data.has("ok")
                    val ok = data.optBoolean("ok", false)
                    if (error != null || (hasOk && !ok)) {
                        _events.tryEmit(SyncEvent.RoomJoinFailed(error))
                        return@Ack
                    }
                    val memberCount = data.optInt("memberCount", 1)
                    _events.tryEmit(SyncEvent.MembersSnapshot(memberCount))
                },
            )
        }

        override fun leaveSession() {
            val roomId = _session.value?.sessionId
            playNextEmitted.set(false)
            _session.value = null
            cancelAllTypingJobs()
            _typingUsers.value = emptySet()
            if (roomId != null) {
                socket.emit(SocketEvents.LEAVE_ROOM, roomId)
            }
        }

        override fun endSession() {
            val roomId = _session.value?.sessionId ?: return
            socket.emit(SocketEvents.END_SESSION, roomId)
        }

        override fun sendChatMessage(
            text: String,
            senderName: String,
        ) {
            val session = _session.value ?: return
            val trimmedText = text.trim().takeIf { it.isNotBlank() } ?: return
            val trimmedName = senderName.trim().ifBlank { "You" }
            val localMessage =
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = session.localUserId,
                    senderName = trimmedName,
                    text = trimmedText,
                    timestamp = System.currentTimeMillis(),
                    isLocal = true,
                )
            _chatMessages.tryEmit(localMessage)
            socket.emit(
                SocketEvents.CHAT_MESSAGE,
                JSONObject()
                    .put("roomId", session.sessionId)
                    .put("text", trimmedText)
                    .put("senderName", trimmedName),
            )
        }

        override fun sendReaction(emoji: String) {
            val session = _session.value ?: return
            val trimmedEmoji = emoji.trim().takeIf { it.isNotBlank() } ?: return
            socket.emit(
                SocketEvents.REACTION,
                JSONObject()
                    .put("roomId", session.sessionId)
                    .put("emoji", trimmedEmoji),
            )
        }

        override fun sendTyping(senderName: String) {
            val session = _session.value ?: return
            val trimmedName = senderName.trim().ifBlank { "You" }
            socket.emit(
                SocketEvents.TYPING,
                JSONObject()
                    .put("roomId", session.sessionId)
                    .put("senderName", trimmedName),
            )
        }

        override fun transferHost(
            roomId: String,
            newHostSocketId: String,
        ) {
            val payload =
                JSONObject().apply {
                    put("roomId", roomId)
                    put("newHostSocketId", newHostSocketId)
                }
            socket.emit(SocketEvents.TRANSFER_HOST, payload)
        }

        override fun setDemocraticMode(
            roomId: String,
            enabled: Boolean,
        ) {
            val payload =
                JSONObject().apply {
                    put("roomId", roomId)
                    put("enabled", enabled)
                }
            socket.emit(SocketEvents.SET_DEMOCRATIC_MODE, payload)
        }

        override fun setAutoApproveQueue(
            roomId: String,
            enabled: Boolean,
        ) {
            val payload =
                JSONObject().apply {
                    put("roomId", roomId)
                    put("enabled", enabled)
                }
            socket.emit(SocketEvents.SET_AUTO_APPROVE_QUEUE, payload)
        }

        override fun requestQueueAdd(
            roomId: String,
            trackId: String,
            trackTitle: String,
        ) {
            val payload =
                JSONObject().apply {
                    put("roomId", roomId)
                    put("track", JSONObject().put("id", trackId).put("title", trackTitle))
                }
            socket.emit(SocketEvents.REQUEST_QUEUE_ADD, payload)
        }

        override fun approveQueueAdd(
            roomId: String,
            trackId: String,
            trackTitle: String,
        ) {
            val payload =
                JSONObject().apply {
                    put("roomId", roomId)
                    put("track", JSONObject().put("id", trackId).put("title", trackTitle))
                }
            socket.emit(SocketEvents.APPROVE_QUEUE_ADD, payload)
        }

        // -----------------------------------------------------------------
        // Private helpers
        // -----------------------------------------------------------------

        private fun handleTrackEnded() {
            val session = _session.value ?: return
            if (!isLocalUserHost(session)) return
            if (!playNextEmitted.compareAndSet(false, true)) return
            val emitted = _events.tryEmit(SyncEvent.PlayNext(session.sessionId))
            if (!emitted) {
                playNextEmitted.compareAndSet(true, false)
            }
        }

        private fun isLocalUserHost(session: Session): Boolean = session.localUserId == session.hostId

        private fun scheduleTypingTimeout(senderId: String) {
            repositoryScope.launch {
                typingJobsMutex.withLock {
                    typingJobs[senderId]?.cancel()
                    typingJobs[senderId] =
                        repositoryScope.launch {
                            delay(TYPING_TIMEOUT_MS)
                            _typingUsers.update { it - senderId }
                            typingJobsMutex.withLock { typingJobs.remove(senderId) }
                        }
                }
            }
        }

        private fun cancelAllTypingJobs() {
            repositoryScope.launch {
                typingJobsMutex.withLock {
                    typingJobs.values.forEach { it.cancel() }
                    typingJobs.clear()
                }
            }
        }
    }
