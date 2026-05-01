package com.musync.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.musync.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var youTubePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar briefly when the invite link has been copied.
    // Dismiss any currently visible snackbar first to avoid stacking.
    val inviteLinkCopiedMessage = stringResource(R.string.invite_link_copied)
    LaunchedEffect(uiState.inviteLinkCopied) {
        if (uiState.inviteLinkCopied) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(inviteLinkCopiedMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Video area with overlay controls
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
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

                // Custom controls overlay
                PlayerOverlayControls(
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    playerReady = youTubePlayer != null,
                    currentSecond = uiState.currentSecond,
                    duration = uiState.duration,
                    trackTitle = uiState.trackTitle,
                    onPlayPause = {
                        val player = youTubePlayer ?: return@PlayerOverlayControls
                        if (uiState.isPlaying) player.pause() else player.play()
                    },
                    onSeek = { second -> youTubePlayer?.seekTo(second) },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                )
            }

            // Share invite button
            if (uiState.inviteLink.isNotEmpty()) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                        viewModel.onInviteLinkCopied()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.share_invite),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerOverlayControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    playerReady: Boolean,
    currentSecond: Float,
    duration: Float,
    trackTitle: String,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Track title
        if (trackTitle.isNotEmpty()) {
            Text(
                text = trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // Seek slider
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

        // Play/pause button and time display
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatSeconds(currentSecond),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )

            if (!playerReady || isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Text(
                text = formatSeconds(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
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
