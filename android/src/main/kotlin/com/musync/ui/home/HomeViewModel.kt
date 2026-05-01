package com.musync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.repository.MusicRepository
import com.musync.sync.QueueManager
import com.musync.util.RoomLinkParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        musicRepository: MusicRepository,
        private val queueManager: QueueManager,
    ) : ViewModel() {
        private val formStateFlow = MutableStateFlow(HomeUiState())

        /**
         * Combined UI state — the player data flows from the repository while the
         * form (join expansion, input, error) is owned locally by the ViewModel.
         */
        val uiState: StateFlow<HomeUiState> =
            combine(
                musicRepository.currentTrack,
                musicRepository.queue,
                formStateFlow,
            ) { track, queue, form ->
                form.copy(currentTrack = track, queue = queue)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeUiState(),
            )

        init {
            queueManager.startListening()
        }

        override fun onCleared() {
            super.onCleared()
            queueManager.stopListening()
        }

        /** Toggles the inline Join Room input. */
        fun onToggleJoinExpanded() {
            formStateFlow.update {
                it.copy(
                    joinExpanded = !it.joinExpanded,
                    // Reset transient state every time the panel opens/closes.
                    joinInput = "",
                    joinError = false,
                )
            }
        }

        /** Updates the value of the join input as the user types. */
        fun onJoinInputChanged(value: String) {
            formStateFlow.update { it.copy(joinInput = value, joinError = false) }
        }

        /**
         * Attempts to parse the current input into a room ID.  On success,
         * exposes the result via [HomeUiState.pendingJoinRoomId] so the screen
         * can perform navigation.  On failure, sets [HomeUiState.joinError].
         */
        fun onJoinConfirm() {
            val parsed = RoomLinkParser.extractRoomId(formStateFlow.value.joinInput)
            if (parsed == null) {
                formStateFlow.update { it.copy(joinError = true) }
            } else {
                formStateFlow.update { it.copy(pendingJoinRoomId = parsed, joinError = false) }
            }
        }

        /** Called by the screen after [HomeUiState.pendingJoinRoomId] has been navigated to. */
        fun onJoinNavigationConsumed() {
            formStateFlow.update {
                it.copy(
                    pendingJoinRoomId = null,
                    joinExpanded = false,
                    joinInput = "",
                )
            }
        }
    }
