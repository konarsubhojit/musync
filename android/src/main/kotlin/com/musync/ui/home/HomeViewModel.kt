package com.musync.ui.home

import androidx.lifecycle.ViewModel
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository
) : ViewModel() {

    val currentTrack: Flow<Track?> = musicRepository.getCurrentTrack()
    val queue: Flow<List<Track>> = musicRepository.getQueue()
}
