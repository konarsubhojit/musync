package com.musync.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Composable that embeds a [YouTubePlayerView] without native player controls.
 * Native controls are disabled via [IFramePlayerOptions] so a custom overlay can be used.
 *
 * The [onPlayerReady] callback delivers the [YouTubePlayer] instance once the iframe is ready.
 * State-change, position, and duration events are forwarded via the remaining callbacks so the
 * caller can update its own UI state.
 */
@Composable
fun YouTubePlayerComposable(
    videoId: String,
    reloadNonce: Int,
    onPlayerReady: (YouTubePlayer) -> Unit,
    onStateChange: (PlayerConstants.PlayerState) -> Unit,
    onError: (PlayerConstants.PlayerError) -> Unit,
    onCurrentSecond: (Float) -> Unit,
    onDuration: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var playerRef by remember { mutableStateOf<YouTubePlayer?>(null) }
    var loadedRequestKey by remember { mutableStateOf("") }

    val youTubePlayerView =
        remember(context) {
            val options =
                IFramePlayerOptions.Builder()
                    .controls(0)
                    .rel(0)
                    .fullscreen(0)
                    .origin("https://www.youtube.com")
                    .build()

            YouTubePlayerView(context).also { view ->
                // Required by the library when calling initialize(...) manually.
                view.enableAutomaticInitialization = false
                view.initialize(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            playerRef = youTubePlayer
                            onPlayerReady(youTubePlayer)
                        }

                        override fun onStateChange(
                            youTubePlayer: YouTubePlayer,
                            state: PlayerConstants.PlayerState,
                        ) {
                            onStateChange(state)
                        }

                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) {
                            onError(error)
                        }

                        override fun onCurrentSecond(
                            youTubePlayer: YouTubePlayer,
                            second: Float,
                        ) {
                            onCurrentSecond(second)
                        }

                        override fun onVideoDuration(
                            youTubePlayer: YouTubePlayer,
                            duration: Float,
                        ) {
                            onDuration(duration)
                        }
                    },
                    options,
                )
            }
        }

    // Register/unregister with the lifecycle so the player pauses/resumes correctly.
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(youTubePlayerView)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(youTubePlayerView)
            youTubePlayerView.release()
        }
    }

    // Load (or reload) the video whenever the videoId or the player reference changes.
    LaunchedEffect(videoId, reloadNonce, playerRef) {
        val player = playerRef
        val requestKey = "$videoId#$reloadNonce"
        if (videoId.isNotEmpty() && player != null && requestKey != loadedRequestKey) {
            player.loadVideo(videoId, 0f)
            loadedRequestKey = requestKey
        }
    }

    AndroidView(
        factory = { youTubePlayerView },
        modifier = modifier,
    )
}
