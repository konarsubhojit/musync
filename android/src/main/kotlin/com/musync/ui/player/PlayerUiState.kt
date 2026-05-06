package com.musync.ui.player

import com.musync.data.model.Track
import com.musync.data.model.YouTubeSearchResult

/** Which secondary tab is selected below the video. */
enum class PlayerTab { Room, Queue }

data class PlayerUiState(
    val videoId: String = "",
    val trackTitle: String = "",
    val isPlaying: Boolean = false,
    val currentSecond: Float = 0f,
    val duration: Float = 0f,
    val isBuffering: Boolean = false,
    val inviteLink: String = "",
    val inviteLinkCopied: Boolean = false,
    /** Currently selected tab below the video. */
    val selectedTab: PlayerTab = PlayerTab.Room,
    /** Whether the floating "controls" overlay is currently visible. */
    val controlsVisible: Boolean = true,
    /** Whether the "Add to queue" bottom sheet is visible. */
    val addToQueueSheetVisible: Boolean = false,
    /** Current text in the "Add to queue" bottom sheet input (URL or search query). */
    val addToQueueInput: String = "",
    /** True when the URL in the add-to-queue input does not parse to a valid YouTube ID. */
    val addToQueueError: Boolean = false,
    /** Whether a YouTube search is currently in progress. */
    val isSearching: Boolean = false,
    /** YouTube search results from the most recent query. */
    val searchResults: List<YouTubeSearchResult> = emptyList(),
    /** True when the most recent YouTube search failed. */
    val searchError: Boolean = false,
    /**
     * Static placeholder for the participant count badge — the underlying
     * presence data is not wired through yet, so we always count just the
     * local listener.  Reserved as state so it can later be driven by the
     * signalling server.
     */
    val participantCount: Int = 1,
    /** The list of tracks currently queued. */
    val queue: List<Track> = emptyList(),
    /** Whether the local user is the room host. */
    val isHost: Boolean = false,
    /** When `true`, show the "Leave room?" confirmation dialog. */
    val showLeaveConfirmDialog: Boolean = false,
    /**
     * When `true`, the screen should navigate back.  Consumed by the UI via
     * [PlayerViewModel.onNavigatedBack].
     */
    val navigateBack: Boolean = false,
    /**
     * When `true`, the room was closed by the host and the guest should see
     * a "Room was closed by host" message before navigating back.
     */
    val roomClosedByHost: Boolean = false,
)
