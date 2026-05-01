package com.musync.data.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val youtubeVideoId: String,
    val durationMs: Long
)
