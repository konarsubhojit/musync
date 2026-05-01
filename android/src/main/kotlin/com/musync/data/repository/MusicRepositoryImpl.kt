package com.musync.data.repository

import com.musync.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl
    @Inject
    constructor() : MusicRepository {
        private val _currentTrack =
            MutableStateFlow<Track?>(
                Track(
                    id = "1",
                    title = "Me at the zoo",
                    artist = "jawed",
                    youtubeVideoId = "jNQXAC9IVRw",
                    durationMs = 19000,
                ),
            )
        private val _queue = MutableStateFlow<List<Track>>(emptyList())

        override val currentTrack: Flow<Track?> = _currentTrack.asStateFlow()

        override val queue: Flow<List<Track>> = _queue.asStateFlow()

        override fun updateQueue(tracks: List<Track>) {
            _queue.value = tracks
        }

        override fun addToQueue(track: Track) {
            _queue.value = _queue.value + track
        }
    }
