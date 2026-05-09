package com.musync.ui.player

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.musync.R
import com.musync.data.model.ChatMessage
import com.musync.data.model.ConnectionState
import com.musync.data.model.Participant
import com.musync.data.model.Track
import com.musync.data.model.YouTubeSearchResult
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer

private const val PLAYER_ERROR_OVERLAY_ALPHA = 0.72f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var youTubePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val inviteLinkCopiedMessage = stringResource(R.string.invite_link_copied)
    LaunchedEffect(uiState.inviteLinkCopied) {
        if (uiState.inviteLinkCopied) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(inviteLinkCopiedMessage)
        }
    }

    val peerJoinedMessage = stringResource(R.string.player_peer_joined)
    val peerLeftMessage = stringResource(R.string.player_peer_left)
    LaunchedEffect(uiState.presenceEvent) {
        when (uiState.presenceEvent) {
            PresenceEvent.PeerJoined -> {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(peerJoinedMessage)
            }
            PresenceEvent.PeerLeft -> {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(peerLeftMessage)
            }
            null -> { /* nothing to show */ }
        }
    }

    val roomClosedMessage = stringResource(R.string.player_room_closed_by_host)
    LaunchedEffect(uiState.navigateBack) {
        if (uiState.navigateBack) {
            if (uiState.roomClosedByHost) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(roomClosedMessage)
            }
            viewModel.onNavigatedBack()
            onBack()
        }
    }

    // Wire the remote-player callbacks for guests whenever the YouTube player becomes available.
    // This allows the host's PLAY/PAUSE/SEEK commands to be applied to the local player.
    LaunchedEffect(youTubePlayer) {
        val player = youTubePlayer ?: return@LaunchedEffect
        viewModel.attachRemotePlayer(
            onPlay = { posMs ->
                val posSeconds = posMs / 1000f
                player.seekTo(posSeconds)
                player.play()
            },
            onPause = { posMs ->
                val posSeconds = posMs / 1000f
                player.seekTo(posSeconds)
                player.pause()
            },
            onSeek = { posMs -> player.seekTo(posMs / 1000f) },
        )
    }

    if (uiState.showLeaveConfirmDialog) {
        LeaveRoomConfirmDialog(
            isHost = uiState.isHost,
            onLeave = viewModel::onLeaveRoomConfirmed,
            onEndSessionForAll = viewModel::onEndSessionForAllConfirmed,
            onDismiss = viewModel::onLeaveRoomDismissed,
        )
    }

    val configuration = LocalConfiguration.current
    val windowSizeClass =
        remember(configuration.screenWidthDp, configuration.screenHeightDp) {
            WindowSizeClass.calculateFromSize(
                DpSize(
                    width = configuration.screenWidthDp.dp,
                    height = configuration.screenHeightDp.dp,
                ),
            )
        }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val canUseFullscreen = isLandscape && windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    var isFullscreenEnabled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(canUseFullscreen) {
        isFullscreenEnabled = canUseFullscreen
    }

    val layoutMode =
        resolvePlayerLayoutMode(
            orientation = configuration.orientation,
            widthSizeClass = windowSizeClass.widthSizeClass,
            fullscreenEnabled = isFullscreenEnabled,
        )

    val view = LocalView.current
    DisposableEffect(layoutMode, view) {
        val activity = view.context.findActivity()
        if (activity == null) {
            return@DisposableEffect onDispose { }
        }

        val insetsController = WindowCompat.getInsetsController(activity.window, view)
        if (layoutMode == PlayerLayoutMode.Fullscreen) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (layoutMode == PlayerLayoutMode.Fullscreen) {
        PlayerVideoArea(
            uiState = uiState,
            youTubePlayer = youTubePlayer,
            onPlayerReady = { player -> youTubePlayer = player },
            onVideoTapped = viewModel::onVideoTapped,
            onControlsInteraction = viewModel::onControlsInteraction,
            onPlaybackStateChanged = viewModel::onPlaybackStateChanged,
            onTrackEnded = viewModel::onTrackEnded,
            onCurrentSecond = viewModel::onCurrentSecond,
            onDurationReceived = viewModel::onDurationReceived,
            onPlayerError = viewModel::onPlayerError,
            onRetryVideoLoad = viewModel::onRetryVideoLoad,
            onUserSeeked = viewModel::onUserSeeked,
            onSkipToNext = viewModel::onSkipToNext,
            canToggleFullscreen = canUseFullscreen,
            isFullscreen = true,
            onToggleFullscreen = { isFullscreenEnabled = !isFullscreenEnabled },
            fillContainer = true,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Scaffold(
            topBar = {
                PlayerTopBar(
                    title = uiState.trackTitle.ifEmpty { stringResource(R.string.app_name) },
                    participantCount = uiState.participants.size.coerceAtLeast(1),
                    onBack = viewModel::onBackPressed,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (uiState.selectedTab == PlayerTab.Queue) {
                    FloatingActionButton(
                        onClick = viewModel::onAddToQueueClicked,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.player_add_to_queue))
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            if (layoutMode == PlayerLayoutMode.TabletSplit) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    PlayerVideoArea(
                        uiState = uiState,
                        youTubePlayer = youTubePlayer,
                        onPlayerReady = { player -> youTubePlayer = player },
                        onVideoTapped = viewModel::onVideoTapped,
                        onControlsInteraction = viewModel::onControlsInteraction,
                        onPlaybackStateChanged = viewModel::onPlaybackStateChanged,
                        onTrackEnded = viewModel::onTrackEnded,
                        onCurrentSecond = viewModel::onCurrentSecond,
                        onDurationReceived = viewModel::onDurationReceived,
                        onPlayerError = viewModel::onPlayerError,
                        onRetryVideoLoad = viewModel::onRetryVideoLoad,
                        onUserSeeked = viewModel::onUserSeeked,
                        onSkipToNext = viewModel::onSkipToNext,
                        canToggleFullscreen = false,
                        isFullscreen = false,
                        onToggleFullscreen = {},
                        modifier =
                            Modifier
                                .weight(1.2f)
                                .fillMaxHeight(),
                    )
                    PlayerTabsSection(
                        uiState = uiState,
                        onTabSelected = viewModel::onTabSelected,
                        onCopyInvite = {
                            clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                            viewModel.onInviteLinkCopied()
                        },
                        onShareInvite = {
                            clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                            viewModel.onInviteLinkCopied()
                        },
                        onChatInputChanged = viewModel::onChatInputChanged,
                        onChatMessageSend = viewModel::onChatMessageSend,
                        onReactionSent = viewModel::onReactionSent,
                        onRemoveFromQueue = viewModel::onRemoveFromQueue,
                        onMoveQueueItem = viewModel::onMoveQueueItem,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    PlayerVideoArea(
                        uiState = uiState,
                        youTubePlayer = youTubePlayer,
                        onPlayerReady = { player -> youTubePlayer = player },
                        onVideoTapped = viewModel::onVideoTapped,
                        onControlsInteraction = viewModel::onControlsInteraction,
                        onPlaybackStateChanged = viewModel::onPlaybackStateChanged,
                        onTrackEnded = viewModel::onTrackEnded,
                        onCurrentSecond = viewModel::onCurrentSecond,
                        onDurationReceived = viewModel::onDurationReceived,
                        onPlayerError = viewModel::onPlayerError,
                        onRetryVideoLoad = viewModel::onRetryVideoLoad,
                        onUserSeeked = viewModel::onUserSeeked,
                        onSkipToNext = viewModel::onSkipToNext,
                        canToggleFullscreen = canUseFullscreen,
                        isFullscreen = false,
                        onToggleFullscreen = { isFullscreenEnabled = !isFullscreenEnabled },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PlayerTabsSection(
                        uiState = uiState,
                        onTabSelected = viewModel::onTabSelected,
                        onCopyInvite = {
                            clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                            viewModel.onInviteLinkCopied()
                        },
                        onShareInvite = {
                            clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                            viewModel.onInviteLinkCopied()
                        },
                        onChatInputChanged = viewModel::onChatInputChanged,
                        onChatMessageSend = viewModel::onChatMessageSend,
                        onReactionSent = viewModel::onReactionSent,
                        onRemoveFromQueue = viewModel::onRemoveFromQueue,
                        onMoveQueueItem = viewModel::onMoveQueueItem,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (uiState.addToQueueSheetVisible) {
                AddToQueueBottomSheet(
                    input = uiState.addToQueueInput,
                    isError = uiState.addToQueueError,
                    isSearching = uiState.isSearching,
                    searchResults = uiState.searchResults,
                    searchError = uiState.searchError,
                    onInputChanged = viewModel::onAddToQueueInputChanged,
                    onSearch = viewModel::onSearch,
                    onConfirm = viewModel::onAddToQueueConfirm,
                    onSearchResultSelected = viewModel::onSearchResultSelected,
                    onDismiss = viewModel::onAddToQueueDismissed,
                )
            }
        }
    }
}

// ── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun PlayerVideoArea(
    uiState: PlayerUiState,
    youTubePlayer: YouTubePlayer?,
    onPlayerReady: (YouTubePlayer) -> Unit,
    onVideoTapped: () -> Unit,
    onControlsInteraction: () -> Unit,
    onPlaybackStateChanged: (isPlaying: Boolean, isBuffering: Boolean) -> Unit,
    onTrackEnded: () -> Unit,
    onCurrentSecond: (Float) -> Unit,
    onDurationReceived: (Float) -> Unit,
    onPlayerError: () -> Unit,
    onRetryVideoLoad: () -> Unit,
    onUserSeeked: (Long) -> Unit,
    onSkipToNext: () -> Unit,
    canToggleFullscreen: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    fillContainer: Boolean = false,
) {
    Box(modifier = modifier.background(Color.Black)) {
        val playbackSurfaceModifier =
            if (fillContainer) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.Center)
            }
        Box(modifier = playbackSurfaceModifier) {
            YouTubePlayerComposable(
                videoId = uiState.videoId,
                reloadNonce = uiState.playerReloadNonce,
                onPlayerReady = onPlayerReady,
                onStateChange = { state ->
                    onPlaybackStateChanged(
                        state == PlayerConstants.PlayerState.PLAYING,
                        state == PlayerConstants.PlayerState.BUFFERING,
                    )
                    if (state == PlayerConstants.PlayerState.ENDED) {
                        onTrackEnded()
                    }
                },
                onError = { onPlayerError() },
                onCurrentSecond = onCurrentSecond,
                onDuration = onDurationReceived,
                modifier = Modifier.fillMaxSize(),
            )

            if (uiState.playerLoadError) {
                PlayerErrorOverlay(onTryAgain = onRetryVideoLoad)
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(canToggleFullscreen) {
                            detectTapGestures(
                                onTap = { onVideoTapped() },
                                onDoubleTap = {
                                    if (canToggleFullscreen) {
                                        onToggleFullscreen()
                                    }
                                },
                            )
                        },
            )

            AnimatedOverlayControls(
                visible = uiState.controlsVisible,
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering,
                playerReady = youTubePlayer != null,
                currentSecond = uiState.currentSecond,
                duration = uiState.duration,
                canSkip = uiState.isHost && uiState.queue.isNotEmpty(),
                canToggleFullscreen = canToggleFullscreen,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onPlayPause = {
                    val player = youTubePlayer ?: return@AnimatedOverlayControls
                    onControlsInteraction()
                    if (uiState.isPlaying) player.pause() else player.play()
                },
                onSeek = { second ->
                    onControlsInteraction()
                    onUserSeeked((second * 1000).toLong())
                    youTubePlayer?.seekTo(second)
                },
                onSkipNext = {
                    onControlsInteraction()
                    onSkipToNext()
                },
            )
        }
    }
}

@Composable
private fun PlayerTabsSection(
    uiState: PlayerUiState,
    onTabSelected: (PlayerTab) -> Unit,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
    onChatInputChanged: (String) -> Unit,
    onChatMessageSend: () -> Unit,
    onReactionSent: (String) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ConnectionStateBanner(connectionState = uiState.connectionState)
        val tabIndex = if (uiState.selectedTab == PlayerTab.Room) 0 else 1
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            androidx.compose.material3.Tab(
                selected = tabIndex == 0,
                onClick = { onTabSelected(PlayerTab.Room) },
                text = { Text(stringResource(R.string.player_tab_room)) },
            )
            androidx.compose.material3.Tab(
                selected = tabIndex == 1,
                onClick = { onTabSelected(PlayerTab.Queue) },
                text = { Text(stringResource(R.string.player_tab_queue)) },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState.selectedTab) {
                PlayerTab.Room ->
                    RoomTab(
                        inviteLink = uiState.inviteLink,
                        participants = uiState.participants,
                        chatMessages = uiState.chatMessages,
                        chatInput = uiState.chatInput,
                        typingUsers = uiState.typingUsers,
                        onCopyInvite = onCopyInvite,
                        onShareInvite = onShareInvite,
                        onChatInputChanged = onChatInputChanged,
                        onChatMessageSend = onChatMessageSend,
                        onReactionSent = onReactionSent,
                    )
                PlayerTab.Queue ->
                    QueueTab(
                        queue = uiState.queue,
                        isHost = uiState.isHost,
                        onRemoveTrack = onRemoveFromQueue,
                        onMoveItem = onMoveQueueItem,
                    )
            }
        }
    }
}

@Composable
private fun ConnectionStateBanner(connectionState: ConnectionState) {
    val textRes =
        when (connectionState) {
            ConnectionState.CONNECTED -> null
            ConnectionState.CONNECTING -> R.string.player_connection_reconnecting
            ConnectionState.DISCONNECTED -> R.string.player_connection_offline
        } ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlayerErrorOverlay(onTryAgain: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = PLAYER_ERROR_OVERLAY_ALPHA),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_video_load_failed),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onTryAgain) {
                Text(stringResource(R.string.player_try_again))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    title: String,
    participantCount: Int,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                )
            }
        },
        actions = {
            ParticipantBadge(count = participantCount)
            Spacer(Modifier.size(8.dp))
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
    )
}

// ── Leave Room Confirmation Dialog ───────────────────────────────────────────

@Composable
private fun LeaveRoomConfirmDialog(
    isHost: Boolean,
    onLeave: () -> Unit,
    onEndSessionForAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_leave_dialog_title)) },
        text = {
            Text(
                if (isHost) {
                    stringResource(R.string.player_leave_dialog_message_host)
                } else {
                    stringResource(R.string.player_leave_dialog_message_guest)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onLeave) {
                Text(stringResource(R.string.player_leave_dialog_confirm))
            }
        },
        dismissButton = {
            if (isHost) {
                TextButton(onClick = onEndSessionForAll) {
                    Text(
                        stringResource(R.string.player_leave_dialog_end_for_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.player_leave_dialog_cancel))
                }
            }
        },
    )
}

@Composable
private fun ParticipantBadge(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Overlay controls ────────────────────────────────────────────────────────

/**
 * Wraps [PlayerOverlayControls] in an [AnimatedVisibility] so it can fade in/out.
 * Extracted as its own composable so the resolver doesn't pick up an outer
 * `ColumnScope.AnimatedVisibility` extension overload by accident.
 */
@Composable
private fun AnimatedOverlayControls(
    visible: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playerReady: Boolean,
    currentSecond: Float,
    duration: Float,
    canSkip: Boolean,
    canToggleFullscreen: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipNext: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerOverlayControls(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            playerReady = playerReady,
            currentSecond = currentSecond,
            duration = duration,
            canSkip = canSkip,
            canToggleFullscreen = canToggleFullscreen,
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onSkipNext = onSkipNext,
        )
    }
}

@Composable
private fun PlayerOverlayControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    playerReady: Boolean,
    currentSecond: Float,
    duration: Float,
    canSkip: Boolean,
    canToggleFullscreen: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipNext: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        if (canToggleFullscreen) {
            IconButton(
                onClick = onToggleFullscreen,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription =
                        if (isFullscreen) {
                            stringResource(R.string.player_exit_fullscreen)
                        } else {
                            stringResource(R.string.player_enter_fullscreen)
                        },
                    tint = Color.White,
                )
            }
        }

        // Centred play/pause (or loading) button — YouTube-style.
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!playerReady || isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                IconButton(
                    onClick = onPlayPause,
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                if (canSkip) {
                    Spacer(Modifier.size(16.dp))
                    IconButton(
                        onClick = onSkipNext,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.player_skip_next),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }

        // Bottom seek bar + time labels.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val sliderMax = duration.coerceAtLeast(1f)
            Slider(
                value = currentSecond.coerceIn(0f, sliderMax),
                onValueChange = onSeek,
                valueRange = 0f..sliderMax,
                enabled = playerReady,
                colors =
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.4f),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = formatSeconds(currentSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Text(
                    text = formatSeconds(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

// ── Tabs ────────────────────────────────────────────────────────────────────

/** Quick-reaction emojis shown in the Room tab. */
private val REACTION_EMOJIS = listOf("🔥", "❤️", "😂", "👏", "😮")

@Composable
private fun RoomTab(
    inviteLink: String,
    participants: List<Participant>,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    typingUsers: Set<String>,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
    onChatInputChanged: (String) -> Unit,
    onChatMessageSend: () -> Unit,
    onReactionSent: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to the bottom when a new message arrives.
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Invite card ───────────────────────────────────────────────────
        if (inviteLink.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.player_invite_card_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = inviteLink,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCopyInvite) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.player_copy_link))
                        }
                        TextButton(onClick = onShareInvite) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.share_invite))
                        }
                    }
                }
            }
        }

        // ── Listeners section ─────────────────────────────────────────────
        Text(
            text = stringResource(R.string.player_participants_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (participants.isEmpty()) {
                ParticipantChip(initials = "?", displayName = stringResource(R.string.player_participant_connecting))
            } else {
                participants.forEach { participant ->
                    val name = participant.displayName.ifBlank { stringResource(R.string.player_participant_anonymous) }
                    ParticipantChip(
                        initials = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        displayName = name,
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // ── Reaction buttons ──────────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            REACTION_EMOJIS.forEach { emoji ->
                Surface(
                    onClick = { onReactionSent(emoji) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(40.dp),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Send reaction $emoji"
                            },
                    ) {
                        Text(text = emoji, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // ── Chat messages ─────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (chatMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(items = chatMessages, key = { it.id }) { message ->
                ChatMessageRow(message = message)
            }
        }

        // ── Typing indicator ──────────────────────────────────────────────
        if (typingUsers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_typing_indicator),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        // ── Chat input ────────────────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = onChatInputChanged,
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        imeAction = ImeAction.Send,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onSend = { onChatMessageSend() },
                    ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(
                onClick = onChatMessageSend,
                enabled = chatInput.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send_button),
                    tint =
                        if (chatInput.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
    val alignment = if (message.isLocal) Alignment.End else Alignment.Start
    val bubbleColor =
        if (message.isLocal) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    val textColor =
        if (message.isLocal) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (!message.isLocal) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Surface(
            color = bubbleColor,
            shape =
                RoundedCornerShape(
                    topStart = if (message.isLocal) 16.dp else 4.dp,
                    topEnd = if (message.isLocal) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text = message.text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ParticipantChip(
    initials: String,
    displayName: String,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QueueTab(
    queue: List<Track>,
    isHost: Boolean,
    onRemoveTrack: (String) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
) {
    if (queue.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.player_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = queue, key = { it.id }) { track ->
                val index = queue.indexOf(track)
                QueueRow(
                    track = track,
                    isHost = isHost,
                    canMoveUp = isHost && index > 0,
                    canMoveDown = isHost && index < queue.lastIndex,
                    onMoveUp = { onMoveItem(index, index - 1) },
                    onMoveDown = { onMoveItem(index, index + 1) },
                    onRemove = { onRemoveTrack(track.id) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isHost: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isHost) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.player_move_up),
                        tint =
                            if (canMoveUp) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.3f,
                                )
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.player_move_down),
                        tint =
                            if (canMoveDown) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.3f,
                                )
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.player_remove_from_queue),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // Guests see a visual drag handle but cannot modify the queue.
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Add-to-queue bottom sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToQueueBottomSheet(
    input: String,
    isError: Boolean,
    isSearching: Boolean,
    searchResults: List<YouTubeSearchResult>,
    searchError: Boolean,
    onInputChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onConfirm: () -> Unit,
    onSearchResultSelected: (YouTubeSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.player_add_to_queue),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                label = { Text(stringResource(R.string.player_add_queue_label)) },
                isError = isError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = onSearch,
                        enabled = input.isNotBlank() && !isSearching,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.player_search_youtube),
                        )
                    }
                },
            )
            if (isError) {
                Text(
                    text = stringResource(R.string.create_room_url_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }

            // ── Search results area ─────────────────────────────────────────
            when {
                isSearching -> {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                searchError -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.player_search_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
                searchResults.isNotEmpty() -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.player_search_results),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(searchResults, key = { it.videoId }) { result ->
                            SearchResultItem(
                                result = result,
                                onClick = { onSearchResultSelected(result) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onConfirm, enabled = input.isNotBlank()) {
                    Text(stringResource(R.string.player_add_queue_confirm))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SearchResultItem(
    result: YouTubeSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = result.thumbnailUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .size(72.dp, 54.dp)
                    .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.channelTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatSeconds(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}

private tailrec fun android.content.Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
