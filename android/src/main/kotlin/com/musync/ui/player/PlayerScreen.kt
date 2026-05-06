package com.musync.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.musync.R
import com.musync.data.model.Track
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer

@OptIn(ExperimentalMaterial3Api::class)
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // ── Video area with overlay controls ─────────────────────────────
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .aspectRatio(16f / 9f),
            ) {
                YouTubePlayerComposable(
                    videoId = uiState.videoId,
                    onPlayerReady = { player -> youTubePlayer = player },
                    onStateChange = { state ->
                        viewModel.onPlaybackStateChanged(
                            isPlaying = state == PlayerConstants.PlayerState.PLAYING,
                            isBuffering = state == PlayerConstants.PlayerState.BUFFERING,
                        )
                    },
                    onCurrentSecond = viewModel::onCurrentSecond,
                    onDuration = viewModel::onDurationReceived,
                    modifier = Modifier.fillMaxSize(),
                )

                // Tap-catcher to toggle the overlay controls.
                val tapInteractionSource =
                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                onClick = viewModel::onVideoTapped,
                                indication = null,
                                interactionSource = tapInteractionSource,
                            ),
                )

                AnimatedOverlayControls(
                    visible = uiState.controlsVisible,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    playerReady = youTubePlayer != null,
                    currentSecond = uiState.currentSecond,
                    duration = uiState.duration,
                    onPlayPause = {
                        val player = youTubePlayer ?: return@AnimatedOverlayControls
                        viewModel.onControlsInteraction()
                        if (uiState.isPlaying) player.pause() else player.play()
                    },
                    onSeek = { second ->
                        viewModel.onControlsInteraction()
                        viewModel.onUserSeeked((second * 1000).toLong())
                        youTubePlayer?.seekTo(second)
                    },
                )
            }

            // ── Tab row ──────────────────────────────────────────────────────
            val tabIndex = if (uiState.selectedTab == PlayerTab.Room) 0 else 1
            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                androidx.compose.material3.Tab(
                    selected = tabIndex == 0,
                    onClick = { viewModel.onTabSelected(PlayerTab.Room) },
                    text = { Text(stringResource(R.string.player_tab_room)) },
                )
                androidx.compose.material3.Tab(
                    selected = tabIndex == 1,
                    onClick = { viewModel.onTabSelected(PlayerTab.Queue) },
                    text = { Text(stringResource(R.string.player_tab_queue)) },
                )
            }

            // ── Tab content ──────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState.selectedTab) {
                    PlayerTab.Room ->
                        RoomTab(
                            inviteLink = uiState.inviteLink,
                            participants = uiState.participants,
                            onCopyInvite = {
                                clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                                viewModel.onInviteLinkCopied()
                            },
                            onShareInvite = {
                                clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                                viewModel.onInviteLinkCopied()
                            },
                        )
                    PlayerTab.Queue -> QueueTab(queue = uiState.queue)
                }
            }
        }

        if (uiState.addToQueueSheetVisible) {
            AddToQueueBottomSheet(
                input = uiState.addToQueueInput,
                isError = uiState.addToQueueError,
                onInputChanged = viewModel::onAddToQueueInputChanged,
                onConfirm = viewModel::onAddToQueueConfirm,
                onDismiss = viewModel::onAddToQueueDismissed,
            )
        }
    }
}

// ── Top bar ─────────────────────────────────────────────────────────────────

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
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
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
            onPlayPause = onPlayPause,
            onSeek = onSeek,
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
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        // Centred play/pause (or loading) button — YouTube-style.
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
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

@Composable
private fun RoomTab(
    inviteLink: String,
    participants: List<com.musync.data.model.Participant>,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Invite card
        if (inviteLink.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
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

        // Listeners section
        Text(
            text = stringResource(R.string.player_participants_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (participants.isEmpty()) {
            // Show a placeholder row while the server hasn't responded yet.
            ParticipantRow(initials = "?", displayName = stringResource(R.string.player_participant_connecting))
        } else {
            participants.forEach { participant ->
                val name = participant.displayName.ifBlank { stringResource(R.string.player_participant_anonymous) }
                ParticipantRow(
                    initials = name.take(1).uppercase(),
                    displayName = name,
                )
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    initials: String,
    displayName: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun QueueTab(queue: List<Track>) {
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = queue, key = { it.id }) { track ->
                QueueRow(track = track)
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
            // Drag handle reserved for future reordering.
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Add-to-queue bottom sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToQueueBottomSheet(
    input: String,
    isError: Boolean,
    onInputChanged: (String) -> Unit,
    onConfirm: () -> Unit,
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
            )
            if (isError) {
                Text(
                    text = stringResource(R.string.create_room_url_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
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

private fun formatSeconds(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}
