package com.musync.ui.player

/**
 * Playback state values reported by the YouTube IFrame Player API.
 *
 * Numeric values match the constants used by the IFrame API's `onStateChange` event:
 *   -1 = unstarted, 0 = ended, 1 = playing, 2 = paused, 3 = buffering, 5 = video cued.
 */
enum class YTPlayerState {
    UNSTARTED,
    ENDED,
    PLAYING,
    PAUSED,
    BUFFERING,
    VIDEO_CUED,
    ;

    companion object {
        fun fromInt(value: Int): YTPlayerState =
            when (value) {
                -1 -> UNSTARTED
                0 -> ENDED
                1 -> PLAYING
                2 -> PAUSED
                3 -> BUFFERING
                5 -> VIDEO_CUED
                else -> UNSTARTED
            }
    }
}

/**
 * Error codes reported by the YouTube IFrame Player API's `onError` event.
 *
 * Numeric values:
 *   2   = invalid parameter value
 *   5   = HTML5 player error
 *   100 = video not found / removed
 *   101 / 150 = video owner has disabled embedded playback
 */
enum class YTPlayerError {
    INVALID_PARAMETER,
    HTML5_ERROR,
    NOT_FOUND,
    EMBEDDING_NOT_ALLOWED,
    UNKNOWN,
    ;

    companion object {
        fun fromInt(value: Int): YTPlayerError =
            when (value) {
                2 -> INVALID_PARAMETER
                5 -> HTML5_ERROR
                100 -> NOT_FOUND
                101, 150 -> EMBEDDING_NOT_ALLOWED
                else -> UNKNOWN
            }
    }
}

/**
 * Minimal interface for controlling a YouTube player.
 * Implemented by [com.musync.ui.player.YouTubeWebViewPlayer].
 */
interface YTPlayerController {
    fun play()

    fun pause()

    fun seekTo(seconds: Float)

    fun loadVideo(
        videoId: String,
        startSeconds: Float,
    )
}
