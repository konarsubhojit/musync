package com.musync.data.repository

import com.musync.data.model.Track
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    fun getCurrentTrack(): Flow<Track?>
    fun getQueue(): Flow<List<Track>>
}
