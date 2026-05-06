package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.BuildConfig
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
import com.musync.sync.PlaybackSyncReceiver
import com.musync.sync.SyncEmitter
import com.musync.util.YouTubeUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

            /** Duration (ms) before the playback overlay controls auto-hide after a tap. */
            internal const val CONTROLS_AUTO_HIDE_MS = 3_000L

            /** Interval (ms) between periodic [SyncEmitter.emitHeartbeat] calls while playing. */
            internal const val HEARTBEAT_INTERVAL_MS = 3_000L

            /** SavedStateHandle key for the initial videoId provided when a host creates a room. */
            internal const val ARG_VIDEO_ID = "videoId"

            /** SavedStateHandle key for the room ID provided via deep link. */
            internal const val ARG_ROOM_ID = "roomId"
        }

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        /** Tracks the active "reset inviteLinkCopied" job so rapid taps cancel the previous timer. */
        private var inviteCopiedResetJob: Job? = null

        /** Tracks the active "auto-hide controls" job so each interaction restarts the timer. */
        private var controlsAutoHideJob: Job? = null

        /** Tracks the active periodic heartbeat emission job. */
        private var heartbeatJob: Job? = null

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

        init {
            val deepLinkRoomId = savedStateHandle.get<String>(ARG_ROOM_ID)?.takeIf { it.isNotBlank() }
            isHost = deepLinkRoomId == null

            if (deepLinkRoomId != null) {
                // Guest mode: joining a session shared via deep link.
                roomId = deepLinkRoomId
                sessionRepository.joinSession(
                    Session(
                        sessionId = roomId,
                        hostId = DEEP_LINK_HOST_ID_PLACEHOLDER,
                        localUserId = UUID.randomUUID().toString(),
                    ),
                )
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$roomId", isHost = false) }
                // Guests listen for host play/pause/seek commands.
                playbackSyncReceiver.startListening()
            } else {
                // Host mode: generate a new session ID and join as the host so that
                // join_room / leave_room are emitted via SessionRepositoryImpl.
                val userId = UUID.randomUUID().toString()
                roomId = UUID.randomUUID().toString()
                sessionRepository.joinSession(
                    Session(
                        sessionId = roomId,
                        hostId = userId,
                        localUserId = userId,
                    ),
                )
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$roomId", isHost = true) }
            }

            // Seed the videoId immediately when the host supplied one so the
            // YouTube player can start loading without waiting for the track
            // flow to emit.
            if (initialVideoId != null) {
                _uiState.update { it.copy(videoId = initialVideoId) }
            }

            viewModelScope.launch {
                musicRepository.currentTrack.collect { track ->
                    _uiState.update { state ->
                        state.copy(
                            // Honour the host-supplied videoId; otherwise fall back
                            // to whatever the repository says is current.
                            videoId = initialVideoId ?: (track?.youtubeVideoId ?: ""),
                            trackTitle = track?.title ?: "",
                        )
                    }
                }
            }

            viewModelScope.launch {
                musicRepository.queue.collect { queue ->
                    _uiState.update { it.copy(queue = queue) }
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
                                // New host should start listening for incoming sync
                                // commands from the server — stop the receiver so
                                // we don't apply our own future emissions to ourselves.
                                playbackSyncReceiver.stopListening()
                            } else {
                                // Former host becomes a guest: start receiving commands.
                                playbackSyncReceiver.startListening()
                                stopHeartbeat()
                            }
                        }
                        else -> Unit
                    }
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
            _uiState.update { it.copy(isPlaying = isPlaying, isBuffering = isBuffering) }
            if (!isHost) return

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
        }

        fun onDurationReceived(duration: Float) {
            _uiState.update { it.copy(duration = duration) }
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
            if (!isHost) return
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

        private fun scheduleControlsHide() {
            controlsAutoHideJob?.cancel()
            controlsAutoHideJob =
                viewModelScope.launch {
                    delay(CONTROLS_AUTO_HIDE_MS)
                    _uiState.update { it.copy(controlsVisible = false) }
                }
        }

        /** Opens the "Add to queue" bottom sheet. */
        fun onAddToQueueClicked() {
            _uiState.update {
                it.copy(
                    addToQueueSheetVisible = true,
                    addToQueueInput = "",
                    addToQueueError = false,
                )
            }
        }

        /** Dismisses the "Add to queue" bottom sheet. */
        fun onAddToQueueDismissed() {
            _uiState.update { it.copy(addToQueueSheetVisible = false) }
        }

        /** Updates the input field of the bottom sheet. */
        fun onAddToQueueInputChanged(value: String) {
            _uiState.update { it.copy(addToQueueInput = value, addToQueueError = false) }
        }

        /**
         * Confirms the bottom sheet input — parses the YouTube URL and, if valid,
         * appends a placeholder track to the local queue via [MusicRepository.addToQueue].
         * Closes the sheet on success; otherwise sets [PlayerUiState.addToQueueError].
         */
        fun onAddToQueueConfirm() {
            val input = _uiState.value.addToQueueInput
            val videoId = YouTubeUrlParser.extractVideoId(input)
            if (videoId == null) {
                _uiState.update { it.copy(addToQueueError = true) }
                return
            }
            musicRepository.addToQueue(
                Track(
                    id = UUID.randomUUID().toString(),
                    title = videoId,
                    artist = "YouTube",
                    youtubeVideoId = videoId,
                    durationMs = 0L,
                ),
            )
            _uiState.update {
                it.copy(
                    addToQueueSheetVisible = false,
                    addToQueueInput = "",
                    addToQueueError = false,
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
    }
