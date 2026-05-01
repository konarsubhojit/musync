package com.musync.ui.createroom

/** UI state for [CreateRoomViewModel]. */
data class CreateRoomUiState(
    /** Raw text the user has typed into the YouTube URL field. */
    val urlInput: String = "",
    /** Parsed video ID; non-null when [urlInput] resolves to a valid YouTube video. */
    val videoId: String? = null,
    /** True when the user has typed something but it has not parsed to a video ID. */
    val urlError: Boolean = false,
    /** Optional display name entered by the user. */
    val displayName: String = "",
    /** Suggested default placeholder for the display-name field. */
    val displayNamePlaceholder: String = "",
    /** True once the user has tapped Start Room and a session ID has been generated. */
    val started: Boolean = false,
    /** The freshly generated session ID; `null` until [started] becomes true. */
    val sessionId: String? = null,
)
