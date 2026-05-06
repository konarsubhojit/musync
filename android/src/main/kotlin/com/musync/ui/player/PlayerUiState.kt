package com.musync.ui.player

import com.musync.data.model.Participant
import com.musync.data.model.Track

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
    /** Current text in the "Add to queue" bottom sheet input. */
    val addToQueueInput: String = "",
    /** True when the URL in the add-to-queue input does not parse to a valid YouTube ID. */
    val addToQueueError: Boolean = false,
    /**
     * Live participant list for this session, populated from `PARTICIPANTS_UPDATED`
     * server events.  Starts with just the local user until the first server
     * broadcast arrives.
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
)
