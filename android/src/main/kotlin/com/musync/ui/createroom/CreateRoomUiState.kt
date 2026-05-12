package com.musync.ui.createroom

import com.musync.data.model.YouTubeSearchResult

/** UI state for [CreateRoomViewModel]. */
data class CreateRoomUiState(
    /** Raw text the user has typed into the YouTube URL/search field. */
    val urlInput: String = "",
    /** Parsed video ID; non-null when [urlInput] resolves to a valid YouTube video. */
    val videoId: String? = null,
    /** Resolved YouTube title for [videoId], when metadata fetch succeeds. */
    val videoTitle: String? = null,
    /** Resolved channel name for [videoId], when metadata fetch succeeds. */
    val channelTitle: String? = null,
    /** True while metadata for [videoId] is being fetched from the server. */
    val isFetchingVideoInfo: Boolean = false,
    /** True when metadata lookup failed for the current [videoId]. */
    val videoInfoError: Boolean = false,
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
    /** True while a YouTube search is in progress. */
    val isSearching: Boolean = false,
    /** Results from the most recent YouTube search. Empty when no search has been run. */
    val searchResults: List<YouTubeSearchResult> = emptyList(),
    /** True when the most recent YouTube search failed. */
    val searchError: Boolean = false,
)
