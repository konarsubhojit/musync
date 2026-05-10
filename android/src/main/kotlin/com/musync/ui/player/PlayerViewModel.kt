package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.BuildConfig
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.data.model.Track
import com.musync.data.model.YouTubeSearchResult
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.RecentRoomsRepository
import com.musync.data.repository.SessionRepository
import com.musync.data.repository.UserPreferencesRepository
import com.musync.data.repository.YouTubeSearchRepository
import com.musync.logging.AppLogger
import com.musync.playback.MediaPlaybackController
import com.musync.sync.PlaybackSyncReceiver
import com.musync.sync.SyncEmitter
import com.musync.util.YouTubeUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val musicRepository: MusicRepository,
        private val sessionRepository: SessionRepository,
        private val syncEmitter: SyncEmitter,
        private val playbackSyncReceiver: PlaybackSyncReceiver,
        private val youTubeSearchRepository: YouTubeSearchRepository,
        private val recentRoomsRepository: RecentRoomsRepository,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val mediaPlaybackController: MediaPlaybackController,
    ) : ViewModel() {
        companion object {
            /**
             * Placeholder host ID used when the app is launched via a deep link.
             * The actual host identity is resolved via the signalling server after
             * joining the session.
             */
            internal const val DEEP_LINK_HOST_ID_PLACEHOLDER = "remote_host"

            /**
             * Base URL used to build the invite deep link.
             *
             * Sourced from `BuildConfig.INVITE_LINK_BASE_URL`, which is configured at
             * build time via the `INVITE_LINK_BASE_URL` Gradle property / environment
             * variable (see `android/build.gradle.kts`).  This lets CI builds point
             * the APK at the real production domain without code changes.
             */
            internal val INVITE_LINK_BASE_URL: String = BuildConfig.INVITE_LINK_BASE_URL

            /** Duration (ms) to show the "link copied" feedback in the UI. */
            internal const val INVITE_COPIED_FEEDBACK_DURATION_MS = 2_000L

            /** Duration (ms) to show a peer-presence (join/leave) notification. */
            internal const val PRESENCE_EVENT_DURATION_MS = 3_000L

            /** Duration (ms) before the playback overlay controls auto-hide after a tap. */
            internal const val CONTROLS_AUTO_HIDE_MS = 3_000L

            /** Interval (ms) between periodic [SyncEmitter.emitHeartbeat] calls while playing. */
            internal const val HEARTBEAT_INTERVAL_MS = 3_000L

            /** SavedStateHandle key for the initial videoId provided when a host creates a room. */
            internal const val ARG_VIDEO_ID = "videoId"

            /** SavedStateHandle key for the room ID provided via deep link. */
            internal const val ARG_ROOM_ID = "roomId"

            /**
             * Debounce window (ms) between input changes and sending a TYPING event.
             * Reduces the number of TYPING socket emissions while the user is typing.
             */
            internal const val TYPING_DEBOUNCE_MS = 500L

            /**
             * Display name used when sending chat messages and typing indicators.
             * Can later be replaced with a user-configurable name from settings.
             */
            internal const val DEFAULT_DISPLAY_NAME = "You"

            /**
             * Maximum number of chat messages retained in [PlayerUiState.chatMessages].
             * When the list exceeds this size, the oldest messages are dropped so that
             * long sessions do not grow memory usage or recomposition cost unboundedly.
             */
            internal const val MAX_CHAT_MESSAGES = 200
            private const val TAG = "PlayerViewModel"
        }

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        /** Tracks the active "reset inviteLinkCopied" job so rapid taps cancel the previous timer. */
        private var inviteCopiedResetJob: Job? = null

        /** Tracks the active "auto-hide controls" job so each interaction restarts the timer. */
        private var controlsAutoHideJob: Job? = null

        /** Tracks the active periodic heartbeat emission job. */
        private var heartbeatJob: Job? = null

        /** Debounce job for TYPING socket event emission. */
        private var typingDebounceJob: Job? = null

        /** Tracks the active job that clears [PlayerUiState.presenceEvent] after a delay. */
        private var presenceResetJob: Job? = null

        /** Tracks the active YouTube search coroutine so rapid calls cancel stale requests. */
        private var searchJob: Job? = null

        /** Tracks the active metadata fetch for add-to-queue URL confirmation. */
        private var videoInfoJob: Job? = null

        /**
         * `true` only when the local player was most recently in a PLAYING state.
         * Used to distinguish a genuine PAUSED transition from non-playing states like
         * ENDED, UNSTARTED, and CUED, which must not trigger a PAUSE socket emission.
         */
        private var wasPlaying = false

        /**
         * The host-supplied video ID, if any.  When present, this overrides the
         * track served by [MusicRepository.currentTrack] so the user can play
         * the specific YouTube video they entered when creating the room.
         */
        private val initialVideoId: String? =
            savedStateHandle.get<String>(ARG_VIDEO_ID)?.takeIf { it.isNotBlank() }

        /**
         * The room ID for this session.
         * - In guest mode (joined via deep link) this is taken from the deep-link URI.
         * - In host mode this is a freshly generated UUID shared via the invite link.
         */
        private val roomId: String

        /**
         * `true` when this client is the session host (i.e. no deep-link room ID was provided).
         * Only the host emits PLAY / PAUSE / SEEK / SYNC_HEARTBEAT events to the server.
         *
         * This value starts as the client-side determination (whether a deep-link roomId was
         * provided), and is updated dynamically when a [SyncEvent.HostTransferred] event is
         * received — allowing the host role to be transferred to any participant at runtime.
         */
        internal var isHost: Boolean

        /** `true` when democratic mode is active (any room member can control playback). */
        internal var isDemocratic: Boolean = false

        init {
            val deepLinkRoomId = savedStateHandle.get<String>(ARG_ROOM_ID)?.takeIf { it.isNotBlank() }
            isHost = deepLinkRoomId == null

            // Generate a stable local user ID for this session.
            val localUserId = UUID.randomUUID().toString()

            if (deepLinkRoomId != null) {
                // Guest mode: joining a session shared via deep link.
                roomId = deepLinkRoomId
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$roomId", isHost = false) }
                // Guests listen for host play/pause/seek commands.
                playbackSyncReceiver.startListening()
            } else {
                // Host mode: generate a new session ID and join as the host so that
                // join_room / leave_room are emitted via SessionRepositoryImpl.
                roomId = UUID.randomUUID().toString()
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$roomId", isHost = true) }
            }

            // Record this room in the local history so the user can rejoin later.
            recentRoomsRepository.addOrUpdateRoom(
                roomId = roomId,
                displayName = roomId,
            )

            // Seed the videoId immediately when the host supplied one so the
            // YouTube player can start loading without waiting for the track
            // flow to emit.
            if (initialVideoId != null) {
                _uiState.update { it.copy(videoId = initialVideoId) }
            }

            // Read the persisted display name and join the session asynchronously.
            viewModelScope.launch {
                val displayName = userPreferencesRepository.displayName.first()
                sessionRepository.joinSession(
                    if (isHost) {
                        Session(
                            sessionId = roomId,
                            hostId = localUserId,
                            localUserId = localUserId,
                            displayName = displayName,
                        )
                    } else {
                        Session(
                            sessionId = roomId,
                            hostId = DEEP_LINK_HOST_ID_PLACEHOLDER,
                            localUserId = localUserId,
                            displayName = displayName,
                        )
                    },
                )
            }

            viewModelScope.launch {
                musicRepository.currentTrack.collect { track ->
                    _uiState.update { state ->
                        state.copy(
                            // Honour the host-supplied videoId; otherwise fall back
                            // to whatever the repository says is current.
                            videoId = initialVideoId ?: (track?.youtubeVideoId ?: ""),
                            trackTitle = track?.title ?: "",
                            playerLoadError = false,
                        )
                    }
                    publishMediaSession()
                }
            }

            viewModelScope.launch {
                musicRepository.queue.collect { queue ->
                    _uiState.update { it.copy(queue = queue) }
                    mediaPlaybackController.updateHasNext(queue.isNotEmpty())
                }
            }

            // React to session-level events (e.g. ROOM_CLOSED broadcast by the host).
            viewModelScope.launch {
                sessionRepository.events.collect { event ->
                    when (event) {
                        is SyncEvent.RoomClosed -> {
                            _uiState.update { it.copy(roomClosedByHost = true, navigateBack = true) }
                        }
                        is SyncEvent.HostTransferred -> {
                            isHost = event.isNowHost
                            _uiState.update { it.copy(isHost = event.isNowHost) }
                            if (event.isNowHost) {
                                // Promoted to host: stop receiving remote commands,
                                // reconcile heartbeat with current playback state.
                                playbackSyncReceiver.stopListening()
                                if (_uiState.value.isPlaying) {
                                    wasPlaying = true
                                    startHeartbeat()
                                } else {
                                    stopHeartbeat()
                                }
                            } else {
                                // Demoted to guest: start receiving commands from the new host.
                                playbackSyncReceiver.startListening()
                                stopHeartbeat()
                                wasPlaying = false
                            }
                        }
                        is SyncEvent.DemocraticModeChanged -> {
                            isDemocratic = event.enabled
                            _uiState.update { it.copy(isDemocraticMode = event.enabled) }
                        }
                        is SyncEvent.AutoApproveQueueChanged -> {
                            _uiState.update { it.copy(autoApproveQueue = event.enabled) }
                        }
                        is SyncEvent.QueueAddRequest -> {
                            _uiState.update { state ->
                                state.copy(
                                    pendingQueueRequests =
                                        state.pendingQueueRequests + (event.trackId to event.trackTitle),
                                )
                            }
                        }
                        is SyncEvent.ConnectionStateChanged -> {
                            _uiState.update { it.copy(connectionState = event.state) }
                        }
                        is SyncEvent.RoomJoinFailed -> {
                            _uiState.update { it.copy(transientError = PlayerTransientError.ROOM_JOIN_FAILED) }
                        }
                        is SyncEvent.PlayNext -> loadNextTrack()
                        is SyncEvent.MembersSnapshot -> Unit // count now derived from participants list
                        is SyncEvent.PeerJoined -> showPresenceEvent(PresenceEvent.PeerJoined)
                        is SyncEvent.PeerLeft -> showPresenceEvent(PresenceEvent.PeerLeft)
                        is SyncEvent.ParticipantsUpdated ->
                            _uiState.update { it.copy(participants = event.participants) }
                        else -> Unit
                    }
                }
            }

            // Collect incoming chat messages.
            viewModelScope.launch {
                sessionRepository.chatMessages.collect { message ->
                    _uiState.update { state ->
                        val updated = (state.chatMessages + message).takeLast(MAX_CHAT_MESSAGES)
                        state.copy(chatMessages = updated)
                    }
                }
            }

            // Collect incoming emoji reactions.
            viewModelScope.launch {
                sessionRepository.reactions.collect { emoji ->
                    _uiState.update { it.copy(pendingReactions = it.pendingReactions + emoji) }
                }
            }

            // Mirror typing users from the repository into UI state.
            viewModelScope.launch {
                sessionRepository.typingUsers.collect { users ->
                    _uiState.update { it.copy(typingUsers = users) }
                }
            }

            // Start the initial auto-hide timer so the overlay controls fade
            // out shortly after the screen appears, mirroring the YouTube app.
            scheduleControlsHide()
        }

        override fun onCleared() {
            super.onCleared()
            stopHeartbeat()
            playbackSyncReceiver.stopListening()
            videoInfoJob?.cancel()
            // Tear down the background-playback notification + foreground service
            // so the user doesn't see a stale "now playing" entry after leaving.
            mediaPlaybackController.stop()
            // Emit leave_room whenever the ViewModel is destroyed (e.g. system back
            // gesture that bypasses the confirmation dialog).
            sessionRepository.leaveSession()
        }

        /**
         * Called when the YouTube player state changes.
         *
         * For the host, this emits the corresponding socket event (PLAY/PAUSE) and manages
         * the periodic heartbeat coroutine.  A PAUSE is only emitted when transitioning from a
         * PLAYING state (`wasPlaying = true`), preventing non-user-initiated state changes such
         * as UNSTARTED and CUED from broadcasting a PAUSE to the room.
         *
         * Note: ENDED (end of track) and explicit PAUSED both flow through the `wasPlaying`
         * branch, so exactly one PAUSE event is emitted in either case. During BUFFERING,
         * `wasPlaying` is intentionally preserved so that a subsequent PAUSED event still
         * results in a PAUSE emission.
         */
        fun onPlaybackStateChanged(
            isPlaying: Boolean,
            isBuffering: Boolean = false,
        ) {
            _uiState.update {
                it.copy(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    playerLoadError = if (isPlaying || isBuffering) false else it.playerLoadError,
                )
            }
            // Mirror the local play/pause to the media-style notification so the
            // lock-screen / Bluetooth controls reflect the current state (#47).
            publishMediaSession()
            if (!isHost && !isDemocratic) return

            val positionMs = (_uiState.value.currentSecond * 1000).toLong()
            when {
                isPlaying -> {
                    wasPlaying = true
                    syncEmitter.emitPlay(roomId, positionMs)
                    startHeartbeat()
                }
                isBuffering -> {
                    // Buffering is a transient state — stop the heartbeat but do not emit PAUSE.
                    // wasPlaying is preserved so a subsequent PAUSED event can still be emitted.
                    stopHeartbeat()
                }
                wasPlaying -> {
                    // True stop: player was playing and is now paused or the track ended.
                    wasPlaying = false
                    syncEmitter.emitPause(roomId, positionMs)
                    stopHeartbeat()
                }
                else -> {
                    // UNSTARTED, CUED, or a repeat non-playing callback — not a meaningful stop.
                    wasPlaying = false
                    stopHeartbeat()
                }
            }
        }

        fun onCurrentSecond(second: Float) {
            _uiState.update { it.copy(currentSecond = second) }
            // Keep the media-session position in sync so the notification's
            // PlaybackState advances correctly while playing (#47).
            mediaPlaybackController.updatePlaybackState(
                isPlaying = _uiState.value.isPlaying,
                positionMs = (second * 1000).toLong(),
            )
        }

        fun onDurationReceived(duration: Float) {
            _uiState.update { it.copy(duration = duration) }
        }

        fun onPlayerError() {
            _uiState.update {
                it.copy(
                    playerLoadError = true,
                    isPlaying = false,
                    isBuffering = false,
                )
            }
        }

        fun onRetryVideoLoad() {
            _uiState.update {
                it.copy(
                    playerLoadError = false,
                    isBuffering = true,
                    playerReloadNonce = it.playerReloadNonce + 1,
                )
            }
        }

        /**
         * Call when the user copies the invite link to the clipboard.
         * Sets [PlayerUiState.inviteLinkCopied] to `true` briefly so the UI
         * can surface a "link copied" confirmation, then resets it automatically.
         * Rapid repeated calls cancel the previous timer and restart it.
         */
        fun onInviteLinkCopied() {
            inviteCopiedResetJob?.cancel()
            _uiState.update { it.copy(inviteLinkCopied = true) }
            inviteCopiedResetJob =
                viewModelScope.launch {
                    delay(INVITE_COPIED_FEEDBACK_DURATION_MS)
                    _uiState.update { it.copy(inviteLinkCopied = false) }
                }
        }

        /** Switches between the Room and Queue tabs below the video. */
        fun onTabSelected(tab: PlayerTab) {
            _uiState.update { it.copy(selectedTab = tab) }
        }

        /**
         * Toggles the video-overlay controls.  When showing them, also (re-)starts
         * the auto-hide timer so the overlay disappears again after a few seconds
         * of inactivity.
         */
        fun onVideoTapped() {
            val currentlyVisible = _uiState.value.controlsVisible
            _uiState.update { it.copy(controlsVisible = !currentlyVisible) }
            if (!currentlyVisible) scheduleControlsHide() else controlsAutoHideJob?.cancel()
        }

        /** Restarts the auto-hide timer; called whenever the user interacts with the overlay. */
        fun onControlsInteraction() {
            if (!_uiState.value.controlsVisible) {
                _uiState.update { it.copy(controlsVisible = true) }
            }
            scheduleControlsHide()
        }

        /**
         * Called when the host user manually seeks to a new position.
         * Emits a [com.musync.sync.SocketEvents.SEEK] event to the server so that guests
         * can seek to the same position.  No-op for guest clients.
         */
        fun onUserSeeked(positionMs: Long) {
            if (!isHost && !isDemocratic) return
            syncEmitter.emitSeek(roomId, positionMs)
        }

        /**
         * Transfers the host role to the participant identified by [newHostSocketId].
         *
         * No-op when the local user is not the current host.  The server validates
         * the request and broadcasts [com.musync.sync.SocketEvents.HOST_TRANSFERRED]
         * to all room members on success.
         *
         * @param newHostSocketId The socket ID of the room member to promote to host.
         */
        fun onTransferHost(newHostSocketId: String) {
            if (!isHost) return
            sessionRepository.transferHost(roomId, newHostSocketId)
        }

        fun onSetDemocraticMode(enabled: Boolean) {
            if (!isHost) return
            sessionRepository.setDemocraticMode(roomId, enabled)
        }

        fun onSetAutoApproveQueue(enabled: Boolean) {
            if (!isHost) return
            sessionRepository.setAutoApproveQueue(roomId, enabled)
        }

        fun onRequestQueueAdd(
            trackId: String,
            trackTitle: String,
        ) {
            sessionRepository.requestQueueAdd(roomId, trackId, trackTitle)
        }

        fun onApproveQueueAdd(
            trackId: String,
            trackTitle: String,
        ) {
            if (!isHost) return
            sessionRepository.approveQueueAdd(roomId, trackId, trackTitle)
            _uiState.update { state ->
                state.copy(pendingQueueRequests = state.pendingQueueRequests.filter { it.first != trackId })
            }
        }

        fun onDismissQueueRequest(trackId: String) {
            if (!isHost) return
            _uiState.update { state ->
                state.copy(pendingQueueRequests = state.pendingQueueRequests.filter { it.first != trackId })
            }
        }

        /**
         * Wires the local YouTube player to [PlaybackSyncReceiver] so that PLAY/PAUSE/SEEK
         * commands from the host are applied to the local player for guests.
         *
         * Should be called from [PlayerScreen] when the YouTube player becomes ready.
         * For host clients this is a no-op because hosts never receive these events
         * (the server relays them only to *other* room members).
         *
         * @param onPlay  Seeks to [positionMs] and starts playback.
         * @param onPause Seeks to [positionMs] and pauses playback.
         * @param onSeek  Seeks to [positionMs] without changing play/pause state.
         */
        fun attachRemotePlayer(
            onPlay: (Long) -> Unit,
            onPause: (Long) -> Unit,
            onSeek: (Long) -> Unit,
        ) {
            if (!isHost) {
                playbackSyncReceiver.attachPlayer(onPlay, onPause, onSeek)
            }
        }

        /**
         * Registers callbacks that the media-style notification ([MediaPlaybackService])
         * will invoke when the user taps Play / Pause / Skip from the lock-screen,
         * status bar, or a Bluetooth headset (#47).  Returned [AutoCloseable] should
         * be invoked when the player surface goes away to drop the registration.
         *
         * The callbacks run on the main thread; implementations should call directly
         * into the local YouTube player (play / pause / seek).  [onSkipNext] also
         * routes through [PlayerViewModel.onSkipToNext] so queue-state updates are
         * broadcast to other room members.
         */
        fun attachNotificationControls(
            onPlay: () -> Unit,
            onPause: () -> Unit,
        ): AutoCloseable {
            val listener =
                object : MediaPlaybackController.Listener {
                    override fun onPlay() {
                        onPlay()
                    }

                    override fun onPause() {
                        onPause()
                    }

                    override fun onSkipNext() {
                        onSkipToNext()
                    }

                    override fun onStop() {
                        // Treat a notification dismiss / Stop as the user
                        // wanting to leave the room.  Mirrors the back-button
                        // confirm-then-leave flow but bypasses the dialog
                        // since it can't be shown from a notification action.
                        sessionRepository.leaveSession()
                        _uiState.update { it.copy(navigateBack = true) }
                        mediaPlaybackController.stop()
                    }
                }
            mediaPlaybackController.setListener(listener)
            // Make sure the service can pick up the latest known state immediately.
            publishMediaSession()
            return AutoCloseable { mediaPlaybackController.clearListener(listener) }
        }

        // ------------------------------------------------------------------
        // Chat
        // ------------------------------------------------------------------

        /** Updates the chat input field and emits a debounced TYPING event. */
        fun onChatInputChanged(text: String) {
            _uiState.update { it.copy(chatInput = text) }
            if (text.isNotBlank()) {
                typingDebounceJob?.cancel()
                typingDebounceJob =
                    viewModelScope.launch {
                        delay(TYPING_DEBOUNCE_MS)
                        sessionRepository.sendTyping(DEFAULT_DISPLAY_NAME)
                    }
            } else {
                // Input cleared — cancel any pending TYPING event to avoid misleading indicators.
                typingDebounceJob?.cancel()
            }
        }

        /** Sends the current chat input as a message and clears the input field. */
        fun onChatMessageSend() {
            val text = _uiState.value.chatInput.trim()
            if (text.isBlank()) return
            typingDebounceJob?.cancel()
            sessionRepository.sendChatMessage(text, DEFAULT_DISPLAY_NAME)
            _uiState.update { it.copy(chatInput = "") }
        }

        /**
         * Sends an ephemeral emoji reaction to all other room members.
         * The emoji is also added locally to [PlayerUiState.pendingReactions] so
         * the sender sees their own reaction immediately.
         */
        fun onReactionSent(emoji: String) {
            sessionRepository.sendReaction(emoji)
            _uiState.update { it.copy(pendingReactions = it.pendingReactions + emoji) }
        }

        /**
         * Called by the UI once it has consumed (displayed) a pending reaction.
         * Removes the first occurrence of [emoji] from [PlayerUiState.pendingReactions].
         */
        fun onReactionConsumed(emoji: String) {
            _uiState.update { state ->
                val updated = state.pendingReactions.toMutableList()
                val idx = updated.indexOf(emoji)
                if (idx >= 0) updated.removeAt(idx)
                state.copy(pendingReactions = updated)
            }
        }

        // ------------------------------------------------------------------

        private fun startHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        val positionMs = (_uiState.value.currentSecond * 1000).toLong()
                        syncEmitter.emitHeartbeat(roomId, positionMs)
                    }
                }
        }

        private fun stopHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }

        /**
         * Surfaces a transient [PresenceEvent] notification (join/leave snackbar).
         * Cancels any pending reset job so that back-to-back events each get a fresh timer.
         */
        private fun showPresenceEvent(event: PresenceEvent) {
            presenceResetJob?.cancel()
            _uiState.update { it.copy(presenceEvent = event) }
            presenceResetJob =
                viewModelScope.launch {
                    delay(PRESENCE_EVENT_DURATION_MS)
                    _uiState.update { it.copy(presenceEvent = null) }
                }
        }

        private fun scheduleControlsHide() {
            controlsAutoHideJob?.cancel()
            controlsAutoHideJob =
                viewModelScope.launch {
                    delay(CONTROLS_AUTO_HIDE_MS)
                    _uiState.update { it.copy(controlsVisible = false) }
                }
        }

        /**
         * Mirrors the current track + play state to [MediaPlaybackController]
         * so the foreground service can render the media-style notification
         * with the right metadata and Play/Pause action (#47).
         */
        private fun publishMediaSession() {
            val state = _uiState.value
            val videoId = state.videoId
            if (videoId.isBlank()) {
                mediaPlaybackController.updateTrack(null)
                return
            }
            mediaPlaybackController.updateTrack(
                MediaPlaybackController.TrackInfo(
                    id = videoId,
                    title = state.trackTitle.ifBlank { videoId },
                    artist = "",
                    durationMs = (state.duration * 1000).toLong(),
                ),
            )
            mediaPlaybackController.updatePlaybackState(
                isPlaying = state.isPlaying,
                positionMs = (state.currentSecond * 1000).toLong(),
            )
            mediaPlaybackController.updateHasNext(state.queue.isNotEmpty())
        }

        /** Opens the "Add to queue" bottom sheet. */
        fun onAddToQueueClicked() {
            videoInfoJob?.cancel()
            videoInfoJob = null
            _uiState.update {
                it.copy(
                    addToQueueSheetVisible = true,
                    addToQueueInput = "",
                    addToQueueError = false,
                    isFetchingVideoInfo = false,
                    addToQueueFetchError = false,
                    searchResults = emptyList(),
                    isSearching = false,
                    searchError = false,
                )
            }
        }

        /** Dismisses the "Add to queue" bottom sheet. */
        fun onAddToQueueDismissed() {
            searchJob?.cancel()
            searchJob = null
            videoInfoJob?.cancel()
            videoInfoJob = null
            _uiState.update {
                it.copy(
                    addToQueueSheetVisible = false,
                    addToQueueError = false,
                    isFetchingVideoInfo = false,
                    addToQueueFetchError = false,
                    searchResults = emptyList(),
                    isSearching = false,
                    searchError = false,
                )
            }
        }

        /** Updates the input field of the bottom sheet. */
        fun onAddToQueueInputChanged(value: String) {
            videoInfoJob?.cancel()
            videoInfoJob = null
            _uiState.update {
                it.copy(
                    addToQueueInput = value,
                    addToQueueError = false,
                    addToQueueFetchError = false,
                )
            }
        }

        /**
         * Confirms the bottom sheet input by parsing a YouTube URL/ID and then
         * resolving metadata for that video before appending the track to the queue.
         * Closes the sheet on success; otherwise surfaces an error in the sheet.
         */
        fun onAddToQueueConfirm() {
            val input = _uiState.value.addToQueueInput
            val videoId = YouTubeUrlParser.extractVideoId(input)
            if (videoId == null) {
                _uiState.update { it.copy(addToQueueError = true) }
                return
            }
            searchJob?.cancel()
            searchJob = null
            videoInfoJob?.cancel()
            videoInfoJob = null
            _uiState.update {
                it.copy(
                    addToQueueError = false,
                    addToQueueFetchError = false,
                    isFetchingVideoInfo = true,
                )
            }
            videoInfoJob =
                viewModelScope.launch {
                    val result = youTubeSearchRepository.fetchVideoInfo(videoId)
                    result.fold(
                        onSuccess = { info ->
                            musicRepository.addToQueue(
                                Track(
                                    id = UUID.randomUUID().toString(),
                                    title = info.title,
                                    artist = info.channelTitle,
                                    youtubeVideoId = videoId,
                                    durationMs = 0L,
                                ),
                            )
                            _uiState.update {
                                it.copy(
                                    addToQueueSheetVisible = false,
                                    addToQueueInput = "",
                                    addToQueueError = false,
                                    isFetchingVideoInfo = false,
                                    addToQueueFetchError = false,
                                    searchResults = emptyList(),
                                    isSearching = false,
                                    searchError = false,
                                )
                            }
                        },
                        onFailure = {
                            _uiState.update {
                                it.copy(
                                    isFetchingVideoInfo = false,
                                    addToQueueFetchError = true,
                                )
                            }
                        },
                    )
                }
        }

        /**
         * Launches a YouTube search for the current [PlayerUiState.addToQueueInput].
         * Cancels any in-flight search before starting a new one so that stale results
         * from a previous query cannot overwrite newer ones.
         * Stores the results in [PlayerUiState.searchResults] on success, or sets
         * [PlayerUiState.searchError] on failure.  No-op when the input is blank.
         */
        fun onSearch() {
            val query = _uiState.value.addToQueueInput.trim()
            if (query.isEmpty()) return
            searchJob?.cancel()
            _uiState.update { it.copy(isSearching = true, searchError = false, searchResults = emptyList()) }
            searchJob =
                viewModelScope.launch {
                    val result = youTubeSearchRepository.search(query)
                    _uiState.update { state ->
                        result.fold(
                            onSuccess = { items ->
                                state.copy(isSearching = false, searchResults = items, searchError = false)
                            },
                            onFailure = {
                                state.copy(isSearching = false, searchError = true)
                            },
                        )
                    }
                }
        }

        /**
         * Adds the given YouTube search result directly to the queue and closes the
         * "Add to queue" bottom sheet.
         *
         * [Track.durationMs] is set to 0 because the YouTube search API does not
         * return video duration; the player resolves the real duration on playback
         * via [PlayerViewModel.onDurationReceived].  This mirrors the behaviour of
         * the URL-paste flow ([onAddToQueueConfirm]).
         */
        fun onSearchResultSelected(result: YouTubeSearchResult) {
            searchJob?.cancel()
            searchJob = null
            videoInfoJob?.cancel()
            videoInfoJob = null
            musicRepository.addToQueue(
                Track(
                    id = UUID.randomUUID().toString(),
                    title = result.title,
                    artist = result.channelTitle,
                    youtubeVideoId = result.videoId,
                    durationMs = 0L,
                ),
            )
            _uiState.update {
                it.copy(
                    addToQueueSheetVisible = false,
                    addToQueueInput = "",
                    addToQueueError = false,
                    isFetchingVideoInfo = false,
                    addToQueueFetchError = false,
                    searchResults = emptyList(),
                    isSearching = false,
                    searchError = false,
                )
            }
        }

        /**
         * Called when the user taps the back button.  Shows a confirmation dialog
         * rather than navigating immediately, so the user can confirm they want to
         * leave the room.
         */
        fun onBackPressed() {
            _uiState.update { it.copy(showLeaveConfirmDialog = true) }
        }

        /** Called when the user dismisses the leave-room confirmation dialog. */
        fun onLeaveRoomDismissed() {
            _uiState.update { it.copy(showLeaveConfirmDialog = false) }
        }

        /**
         * Called when the user confirms they want to leave the room.
         * Emits `leave_room` to the server and signals the UI to navigate back.
         */
        fun onLeaveRoomConfirmed() {
            sessionRepository.leaveSession()
            _uiState.update { it.copy(showLeaveConfirmDialog = false, navigateBack = true) }
        }

        /**
         * Called when the host confirms they want to end the session for all participants.
         * Emits `end_session` to the server (which broadcasts `ROOM_CLOSED` to everyone)
         * and signals the UI to navigate back.
         */
        fun onEndSessionForAllConfirmed() {
            sessionRepository.endSession()
            _uiState.update { it.copy(showLeaveConfirmDialog = false, navigateBack = true) }
        }

        /**
         * Called by the UI once it has consumed the [PlayerUiState.navigateBack] signal.
         * Resets the flag so it is not re-consumed on recomposition.
         */
        fun onNavigatedBack() {
            _uiState.update { it.copy(navigateBack = false, roomClosedByHost = false) }
        }

        fun onTransientErrorShown() {
            _uiState.update { it.copy(transientError = null) }
        }

        /**
         * Called when the YouTube player signals that the current track has ended.
         * Notifies [SessionRepository] so it can emit [SyncEvent.PlayNext] when the local
         * user is the session host.  Auto-advance is then handled in the events collector.
         */
        fun onTrackEnded() {
            sessionRepository.onPlayerStateChanged(PlayerState.ENDED)
        }

        /**
         * Removes the track identified by [trackId] from the local queue and broadcasts the
         * updated queue to the room via [SyncEmitter].  No-op for guest clients.
         */
        fun onRemoveFromQueue(trackId: String) {
            if (!isHost) return
            val updatedQueue = _uiState.value.queue.filter { it.id != trackId }
            musicRepository.updateQueue(updatedQueue)
            emitQueueUpdatedOrShowError(updatedQueue)
        }

        /**
         * Moves the queue item at [fromIndex] to [toIndex] and broadcasts the updated queue.
         * No-op for guest clients or when either index is out of bounds.
         */
        fun onMoveQueueItem(
            fromIndex: Int,
            toIndex: Int,
        ) {
            if (!isHost) return
            val current = _uiState.value.queue.toMutableList()
            if (fromIndex < 0 || fromIndex >= current.size || toIndex < 0 || toIndex >= current.size) return
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            musicRepository.updateQueue(current)
            emitQueueUpdatedOrShowError(current)
        }

        /**
         * Skips the currently playing track and loads the first track from the queue.
         * No-op for guest clients or when the queue is empty.
         */
        fun onSkipToNext() {
            if (!isHost) return
            loadNextTrack()
        }

        /**
         * Loads and plays the first track in the queue, removes it from the queue, and
         * broadcasts the updated queue to the room.  No-op when the queue is empty.
         */
        private fun loadNextTrack() {
            val queue = _uiState.value.queue
            if (queue.isEmpty()) return
            val nextTrack = queue[0]
            val updatedQueue = queue.drop(1)
            musicRepository.updateQueue(updatedQueue)
            _uiState.update {
                it.copy(
                    videoId = nextTrack.youtubeVideoId,
                    playerLoadError = false,
                )
            }
            emitQueueUpdatedOrShowError(updatedQueue)
        }

        private fun emitQueueUpdatedOrShowError(updatedQueue: List<Track>) {
            runCatching { syncEmitter.emitQueueUpdated(roomId, updatedQueue) }
                .onFailure {
                    AppLogger.w(TAG, "Queue sync emit failed.")
                    _uiState.update { state ->
                        state.copy(transientError = PlayerTransientError.QUEUE_SYNC_FAILED)
                    }
                }
        }
    }
