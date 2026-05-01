package com.musync.ui.home

import androidx.lifecycle.ViewModel
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.sync.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val musicRepository: MusicRepository,
        private val queueManager: QueueManager,
    ) : ViewModel() {
        val currentTrack: Flow<Track?> = musicRepository.currentTrack
        val queue: Flow<List<Track>> = musicRepository.queue

        init {
            queueManager.startListening()
        }

        override fun onCleared() {
            super.onCleared()
            queueManager.stopListening()
        }
    }
