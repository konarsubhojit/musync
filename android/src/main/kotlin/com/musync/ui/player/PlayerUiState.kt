package com.musync.ui.player

import com.musync.data.model.ChatMessage
import com.musync.data.model.Participant
import com.musync.data.model.Track
import com.musync.data.model.YouTubeSearchResult

/** Which secondary tab is selected below the video. */
enum class PlayerTab { Room, Queue }

/**
 * Represents a transient peer-presence event to be shown as a notification.
 * Consumed by the UI via a [androidx.compose.runtime.LaunchedEffect].
 */
sealed class PresenceEvent {
    /** A new listener joined the room. */
    data object PeerJoined : PresenceEvent()

    /** A listener left the room. */
    data object PeerLeft : PresenceEvent()
}

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
    /** True when the add-to-queue flow is fetching title/channel metadata for a pasted URL. */
    val isFetchingVideoInfo: Boolean = false,
    /** True when metadata lookup for a pasted URL failed. */
    val addToQueueFetchError: Boolean = false,
    /** Whether a YouTube search is currently in progress. */
    val isSearching: Boolean = false,
    /** YouTube search results from the most recent query. */
    val searchResults: List<YouTubeSearchResult> = emptyList(),
    /** True when the most recent YouTube search failed. */
    val searchError: Boolean = false,
    /**
     * Live participant list for this session, populated from `PARTICIPANTS_UPDATED`
     * server events.  Empty until the first server broadcast arrives.
     */
    val participants: List<Participant> = emptyList(),
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
    /**
     * Transient peer-presence notification surfaced as a snackbar.
     * Set by the ViewModel when a peer joins or leaves; cleared automatically
     * after [PlayerViewModel.PRESENCE_EVENT_DURATION_MS].
     */
    val presenceEvent: PresenceEvent? = null,
    /** Ordered list of chat messages visible in the Room tab. */
    val chatMessages: List<ChatMessage> = emptyList(),
    /** Current text in the chat input field. */
    val chatInput: String = "",
    /**
     * Socket IDs of participants who are currently composing a message.
     *
     * Entries auto-expire after the typing inactivity window; the UI shows
     * a typing indicator while this set is non-empty.
     */
    val typingUsers: Set<String> = emptySet(),
    /**
     * Queue of ephemeral emoji reactions awaiting display as floating overlays.
     * Each entry is an emoji string; entries are consumed and removed by the UI.
     */
    val pendingReactions: List<String> = emptyList(),
    /** Whether democratic mode is active (any room member can control playback). */
    val isDemocraticMode: Boolean = false,
    /** Whether guest queue additions are auto-approved by the server. */
    val autoApproveQueue: Boolean = true,
    /**
     * Pending queue addition requests from guests (host only, when autoApproveQueue=false).
     * Each entry is a Pair of (trackId, trackTitle).
     */
    val pendingQueueRequests: List<Pair<String, String>> = emptyList(),
)
