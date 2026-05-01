package com.musync.data.repository

import com.musync.data.model.Track
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    val currentTrack: Flow<Track?>

    val queue: Flow<List<Track>>

    fun updateQueue(tracks: List<Track>)
}
