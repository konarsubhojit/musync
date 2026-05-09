package com.musync.ui.createroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.repository.UserPreferencesRepository
import com.musync.data.repository.YouTubeSearchRepository
import com.musync.logging.AppLogger
import com.musync.util.YouTubeUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

/**
 * ViewModel backing [CreateRoomScreen].  Parses the YouTube URL the user types,
 * reports validation state in real-time, and produces the session/video IDs
 * required to navigate to the player.
 *
 * The user's display name is loaded from [UserPreferencesRepository] on init
 * and saved back when the room is started.
 */
@HiltViewModel
class CreateRoomViewModel
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val youTubeSearchRepository: YouTubeSearchRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                CreateRoomUiState(
                    displayNamePlaceholder = generateDefaultName(),
                ),
            )
        val uiState: StateFlow<CreateRoomUiState> = _uiState.asStateFlow()
        private var videoInfoJob: Job? = null

        init {
            // Pre-fill the display name field with the value saved from the last session.
            // Only applied when the field is still blank to avoid clobbering edits already
            // made by the user before the DataStore read completes.
            viewModelScope.launch {
                val savedName = userPreferencesRepository.displayName.first()
                if (savedName.isNotBlank()) {
                    _uiState.update { current ->
                        if (current.displayName.isBlank()) current.copy(displayName = savedName) else current
                    }
                }
            }
        }

        /** Called whenever the URL field text changes.  Re-parses and updates validation. */
        fun onUrlChanged(input: String) {
            videoInfoJob?.cancel()
            videoInfoJob = null
            val videoId = YouTubeUrlParser.extractVideoId(input)
            _uiState.update {
                it.copy(
                    urlInput = input,
                    videoId = videoId,
                    videoTitle = null,
                    channelTitle = null,
                    isFetchingVideoInfo = videoId != null,
                    videoInfoError = false,
                    urlError = input.isNotBlank() && videoId == null,
                )
            }
            if (videoId == null) {
                return
            }
            videoInfoJob =
                viewModelScope.launch {
                    val result = youTubeSearchRepository.fetchVideoInfo(videoId)
                    _uiState.update { state ->
                        if (state.videoId != videoId) {
                            state
                        } else {
                            result.fold(
                                onSuccess = { info ->
                                    state.copy(
                                        videoTitle = info.title,
                                        channelTitle = info.channelTitle,
                                        isFetchingVideoInfo = false,
                                        videoInfoError = false,
                                    )
                                },
                                onFailure = {
                                    state.copy(
                                        isFetchingVideoInfo = false,
                                        videoInfoError = true,
                                    )
                                },
                            )
                        }
                    }
                }
        }

        /** Called when the user edits the display-name field. */
        fun onDisplayNameChanged(input: String) {
            _uiState.update { it.copy(displayName = input) }
        }

        /**
         * Called when the user taps "Start Room".  Generates a new session ID,
         * persists the display name, and flips [CreateRoomUiState.started] so
         * the screen can navigate.  No-op if no valid video ID has been entered.
         */
        fun onStartRoom() {
            val current = _uiState.value
            if (current.videoId == null) {
                AppLogger.w(TAG, "Start room ignored: no valid YouTube ID parsed.")
                return
            }
            val sessionId = UUID.randomUUID().toString()
            AppLogger.i(TAG, "Starting room $sessionId for video ${current.videoId}")
            // Sanitise the display name (trim + 50-char cap) to match server behaviour,
            // then persist it so it is pre-filled next time.
            val sanitisedName = current.displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
            viewModelScope.launch {
                userPreferencesRepository.saveDisplayName(sanitisedName)
            }
            _uiState.update {
                it.copy(
                    displayName = sanitisedName,
                    started = true,
                    sessionId = sessionId,
                )
            }
        }

        /** Resets [CreateRoomUiState.started] after navigation has been consumed. */
        fun onNavigationConsumed() {
            _uiState.update { it.copy(started = false) }
        }

        private fun generateDefaultName(): String = "Listener #${Random.nextInt(100, 1000)}"

        private companion object {
            const val TAG = "CreateRoomViewModel"
            const val MAX_DISPLAY_NAME_LENGTH = 50
        }
    }
