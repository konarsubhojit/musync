package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.model.Session
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
        }

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        init {
            // Join the room supplied via deep link (listen.yourdomain.com/room/{roomId}).
            savedStateHandle.get<String>("roomId")
                ?.takeIf { it.isNotBlank() }
                ?.let { roomId ->
                    sessionRepository.joinSession(
                        Session(
                            sessionId = roomId,
                            hostId = DEEP_LINK_HOST_ID_PLACEHOLDER,
                            localUserId = UUID.randomUUID().toString(),
                        ),
                    )
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
    }
