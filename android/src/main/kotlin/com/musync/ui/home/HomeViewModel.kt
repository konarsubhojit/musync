package com.musync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.remote.RoomStatusChecker
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.RecentRoomsRepository
import com.musync.logging.AppLogger
import com.musync.sync.QueueManager
import com.musync.util.RoomLinkParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        musicRepository: MusicRepository,
        private val queueManager: QueueManager,
        private val recentRoomsRepository: RecentRoomsRepository,
        private val roomStatusChecker: RoomStatusChecker,
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
            loadRecentRoomsAndStatuses()
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
                AppLogger.w(
                    TAG,
                    "Rejected invalid join input: \"${formStateFlow.value.joinInput}\"",
                )
                formStateFlow.update { it.copy(joinError = true) }
            } else {
                AppLogger.i(TAG, "Join confirmed for room $parsed")
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

        /** Clears the full recent-rooms history. */
        fun onClearHistory() {
            recentRoomsRepository.clearHistory()
            formStateFlow.update { it.copy(recentRooms = emptyList(), recentRoomsStatus = emptyMap()) }
            AppLogger.i(TAG, "Recent rooms history cleared")
        }

        /**
         * Loads the persisted recent-rooms list and concurrently fetches the live
         * status for each room from the server.
         */
        private fun loadRecentRoomsAndStatuses() {
            viewModelScope.launch {
                val rooms = recentRoomsRepository.getRecentRooms()
                formStateFlow.update { it.copy(recentRooms = rooms) }

                if (rooms.isEmpty()) return@launch

                // Fetch statuses concurrently — a failed check for one room must
                // not block the others.
                val statusMap =
                    rooms
                        .map { room ->
                            async {
                                room.roomId to roomStatusChecker.getStatus(room.roomId)
                            }
                        }.awaitAll()
                        .toMap()

                formStateFlow.update { it.copy(recentRoomsStatus = statusMap) }
            }
        }

        private companion object {
            const val TAG = "HomeViewModel"
        }
    }
