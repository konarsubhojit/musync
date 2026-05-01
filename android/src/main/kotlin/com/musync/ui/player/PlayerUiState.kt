package com.musync.ui.player

data class PlayerUiState(
    val videoId: String = "",
    val trackTitle: String = "",
    val isPlaying: Boolean = false,
    val currentSecond: Float = 0f,
    val duration: Float = 0f,
    val isBuffering: Boolean = false
)
