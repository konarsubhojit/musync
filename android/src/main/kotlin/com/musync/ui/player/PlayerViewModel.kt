package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.model.Session
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
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
        }

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        /** Tracks the active "reset inviteLinkCopied" job so rapid taps cancel the previous timer. */
        private var inviteCopiedResetJob: Job? = null

        init {
            val roomId = savedStateHandle.get<String>("roomId")?.takeIf { it.isNotBlank() }

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

            viewModelScope.launch {
                musicRepository.currentTrack.collect { track ->
                    _uiState.update { state ->
                        state.copy(
                            videoId = track?.youtubeVideoId ?: "",
                            trackTitle = track?.title ?: "",
                        )
                    }
                }
            }
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
            inviteCopiedResetJob = viewModelScope.launch {
                delay(INVITE_COPIED_FEEDBACK_DURATION_MS)
                _uiState.update { it.copy(inviteLinkCopied = false) }
            }
        }
    }
