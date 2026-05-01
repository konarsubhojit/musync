package com.musync.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val musicRepository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            musicRepository.getCurrentTrack().collect { track ->
                _uiState.update { state ->
                    state.copy(
                        videoId = track?.youtubeVideoId ?: "",
                        trackTitle = track?.title ?: ""
                    )
                }
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean = false) {
        _uiState.update { it.copy(isPlaying = isPlaying, isBuffering = isBuffering) }
    }

    fun onCurrentSecond(second: Float) {
        _uiState.update { it.copy(currentSecond = second) }
    }

    fun onDurationReceived(duration: Float) {
        _uiState.update { it.copy(duration = duration) }
    }
}
