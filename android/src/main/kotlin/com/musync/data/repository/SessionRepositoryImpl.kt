package com.musync.data.repository

import com.musync.data.model.ChatMessage
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.sync.SocketEvents
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
            /**
             * Buffer capacity for outgoing sync events.
             *
             * If collectors fall behind, events will be dropped once this buffer is
             * full (because [handleTrackEnded] uses [MutableSharedFlow.tryEmit]).
             * On a failed emission the guard is reverted so the next ENDED callback
             * can retry rather than permanently suppressing [SyncEvent.PlayNext] for
             * the current track.
             */
            private const val EVENT_BUFFER_CAPACITY = 8

            /** Buffer capacity for incoming chat messages. */
            private const val CHAT_BUFFER_CAPACITY = 64

            /**
             * How long (ms) a typing indicator persists after the last TYPING event
             * is received.  If no new TYPING arrives within this window the user is
             * removed from [_typingUsers].
             */
            internal const val TYPING_TIMEOUT_MS = 3_000L
        }

        /**
         * Internal scope used for typing-timeout coroutines.  Uses [SupervisorJob] so
         * that a cancelled child (typing timeout) does not cancel the whole scope.
         */
        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _session = MutableStateFlow<Session?>(null)
        private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
        private val _chatMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = CHAT_BUFFER_CAPACITY)
        private val _reactions = MutableSharedFlow<String>(extraBufferCapacity = CHAT_BUFFER_CAPACITY)
        private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())

        /**
         * Guards against duplicate [SyncEvent.PlayNext] emissions for the same
         * track.  Set to `true` on the first ENDED callback; reset to `false`
         * when a new track starts (PLAYING state) or when the session changes.
         */
        private val playNextEmitted = AtomicBoolean(false)

        /**
         * Tracks pending "stop-typing" cleanup coroutines keyed by senderId so that
         * each new TYPING event from the same user resets the timeout.
         */
        private val typingJobs = mutableMapOf<String, Job>()

        init {
            socket.on(SocketEvents.ROOM_CLOSED) {
                _session.value = null
                _events.tryEmit(SyncEvent.RoomClosed)
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
                // Add the typing user and schedule auto-removal after the timeout.
                _typingUsers.value = _typingUsers.value + senderId
                scheduleTypingTimeout(senderId)
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
            socket.connect()
            socket.emit(SocketEvents.JOIN_ROOM, session.sessionId)
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
            // Emit locally first so the sender sees the message immediately.
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
            // Broadcast to other room members.
            socket.emit(
                SocketEvents.CHAT_MESSAGE,
                JSONObject()
                    .put("roomId", session.sessionId)
                    .put("text", trimmedText)
                    .put("senderId", session.localUserId)
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
                    .put("senderId", session.localUserId)
                    .put("senderName", trimmedName),
            )
        }

        // -----------------------------------------------------------------
        // Private helpers
        // -----------------------------------------------------------------

        private fun handleTrackEnded() {
            val session = _session.value ?: return
            if (!isLocalUserHost(session)) return
            // compareAndSet returns true only for the first caller, preventing
            // duplicate PLAY_NEXT emissions even under concurrent callbacks.
            if (!playNextEmitted.compareAndSet(false, true)) return
            val emitted = _events.tryEmit(SyncEvent.PlayNext(session.sessionId))
            if (!emitted) {
                // If the SharedFlow could not accept the event, clear the guard so
                // a subsequent callback can retry instead of permanently
                // suppressing PlayNext for this track.
                playNextEmitted.compareAndSet(true, false)
            }
        }

        private fun isLocalUserHost(session: Session): Boolean = session.localUserId == session.hostId

        /**
         * Resets (or starts) the inactivity coroutine that removes [senderId] from
         * [_typingUsers] after [TYPING_TIMEOUT_MS] ms of silence.
         */
        private fun scheduleTypingTimeout(senderId: String) {
            typingJobs[senderId]?.cancel()
            typingJobs[senderId] =
                repositoryScope.launch {
                    delay(TYPING_TIMEOUT_MS)
                    _typingUsers.value = _typingUsers.value - senderId
                    typingJobs.remove(senderId)
                }
        }

        private fun cancelAllTypingJobs() {
            typingJobs.values.forEach { it.cancel() }
            typingJobs.clear()
        }
    }


