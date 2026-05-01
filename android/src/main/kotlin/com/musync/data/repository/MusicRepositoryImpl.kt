package com.musync.data.repository

import com.musync.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor() : MusicRepository {

    private val _currentTrack = MutableStateFlow<Track?>(null)
    private val _queue = MutableStateFlow<List<Track>>(emptyList())

    override fun getCurrentTrack(): Flow<Track?> = _currentTrack.asStateFlow()

    override fun getQueue(): Flow<List<Track>> = _queue.asStateFlow()
}
