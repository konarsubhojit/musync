package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.model.Session
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
import com.musync.util.YouTubeUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    ) : ViewModel() {
        companion object {
            /**
             * Placeholder host ID used when the app is launched via a deep link.
             * The actual host identity is resolved via the signalling server after
             * joining the session.
             */
            internal const val DEEP_LINK_HOST_ID_PLACEHOLDER = "remote_host"

            /** Base URL used to build the invite deep link.
             * TODO: Replace with the production domain before release. */
            internal const val INVITE_LINK_BASE_URL = "https://listen.yourdomain.com/room"

            /** Duration (ms) to show the "link copied" feedback in the UI. */
            internal const val INVITE_COPIED_FEEDBACK_DURATION_MS = 2_000L

            /** Duration (ms) before the playback overlay controls auto-hide after a tap. */
            internal const val CONTROLS_AUTO_HIDE_MS = 3_000L

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

        /**
         * The host-supplied video ID, if any.  When present, this overrides the
         * track served by [MusicRepository.currentTrack] so the user can play
         * the specific YouTube video they entered when creating the room.
         */
        private val initialVideoId: String? =
            savedStateHandle.get<String>(ARG_VIDEO_ID)?.takeIf { it.isNotBlank() }

        init {
            val roomId = savedStateHandle.get<String>(ARG_ROOM_ID)?.takeIf { it.isNotBlank() }

            if (roomId != null) {
                // Guest mode: joining a session shared via deep link.
                sessionRepository.joinSession(
                    Session(
                        sessionId = roomId,
                        hostId = DEEP_LINK_HOST_ID_PLACEHOLDER,
                        localUserId = UUID.randomUUID().toString(),
                    ),
                )
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$roomId") }
            } else {
                // Host mode: generate a new session ID so the invite link can be shared.
                val sessionId = UUID.randomUUID().toString()
                _uiState.update { it.copy(inviteLink = "$INVITE_LINK_BASE_URL/$sessionId") }
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

            // Start the initial auto-hide timer so the overlay controls fade
            // out shortly after the screen appears, mirroring the YouTube app.
            scheduleControlsHide()
        }

        fun onPlaybackStateChanged(
            isPlaying: Boolean,
            isBuffering: Boolean = false,
        ) {
            _uiState.update { it.copy(isPlaying = isPlaying, isBuffering = isBuffering) }
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
    }
