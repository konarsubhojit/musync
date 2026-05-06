package com.musync.ui.createroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import com.musync.util.YouTubeUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
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
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                CreateRoomUiState(
                    displayNamePlaceholder = generateDefaultName(),
                ),
            )
        val uiState: StateFlow<CreateRoomUiState> = _uiState.asStateFlow()

        init {
            // Pre-fill the display name field with the value saved from the last session.
            viewModelScope.launch {
                val savedName = userPreferencesRepository.displayName.first()
                if (savedName.isNotBlank()) {
                    _uiState.update { it.copy(displayName = savedName) }
                }
            }
        }

        /** Called whenever the URL field text changes.  Re-parses and updates validation. */
        fun onUrlChanged(input: String) {
            val videoId = YouTubeUrlParser.extractVideoId(input)
            _uiState.update {
                it.copy(
                    urlInput = input,
                    videoId = videoId,
                    urlError = input.isNotBlank() && videoId == null,
                )
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
            // Persist the display name so it is pre-filled next time.
            viewModelScope.launch {
                userPreferencesRepository.saveDisplayName(current.displayName)
            }
            _uiState.update {
                it.copy(
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
        }
    }
