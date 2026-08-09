package com.musync.ui.player

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.musync.R
import com.musync.logging.AppLogger
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/** Shared log tag for the YouTube player. */
private const val PLAYER_TAG = "YTPlayer"

/** A literal YouTube video ID; anything else is rejected before reaching the player. */
private val VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Domain the embedded player reports as its origin.
 *
 * The library loads its page with `loadDataWithBaseURL(origin, …)`, so the origin
 * doubles as the page's base URL. Its default (`https://<package name>`) is not a
 * real domain, and YouTube's embed checks answer synthetic origins with the
 * 150-series error ("playback on other websites has been disabled") even for videos
 * whose owners do allow embedding — the failure mode this app hit repeatedly. The
 * library's own documentation recommends this value for that reason.
 */
private const val PLAYER_ORIGIN = "https://www.youtube.com"

/** Maps the library's playback states onto the app-wide [YTPlayerState] vocabulary. */
private fun PlayerConstants.PlayerState.toYTPlayerState(): YTPlayerState =
    when (this) {
        PlayerConstants.PlayerState.ENDED -> YTPlayerState.ENDED
        PlayerConstants.PlayerState.PLAYING -> YTPlayerState.PLAYING
        PlayerConstants.PlayerState.PAUSED -> YTPlayerState.PAUSED
        PlayerConstants.PlayerState.BUFFERING -> YTPlayerState.BUFFERING
        PlayerConstants.PlayerState.VIDEO_CUED -> YTPlayerState.VIDEO_CUED
        PlayerConstants.PlayerState.UNSTARTED, PlayerConstants.PlayerState.UNKNOWN -> YTPlayerState.UNSTARTED
    }

/** Maps the library's error codes onto the app-wide [YTPlayerError] vocabulary. */
private fun PlayerConstants.PlayerError.toYTPlayerError(): YTPlayerError =
    when (this) {
        PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> YTPlayerError.INVALID_PARAMETER
        PlayerConstants.PlayerError.HTML_5_PLAYER -> YTPlayerError.HTML5_ERROR
        PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> YTPlayerError.NOT_FOUND
        PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> YTPlayerError.EMBEDDING_NOT_ALLOWED
        // Reported when the embed is served without a referer the API trusts; it is
        // not actionable for the user, so it is surfaced as a generic failure.
        PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER -> YTPlayerError.UNKNOWN
        PlayerConstants.PlayerError.UNKNOWN -> YTPlayerError.UNKNOWN
    }

/**
 * [YTPlayerController] backed by the library's [YouTubePlayer].
 *
 * Every call is marshalled onto the main thread by the library itself, so the sync
 * layer can drive playback from whichever thread a socket event arrives on.
 */
private class LibraryYTPlayerController(
    private val player: YouTubePlayer,
) : YTPlayerController {
    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(seconds: Float) = player.seekTo(seconds)

    override fun loadVideo(
        videoId: String,
        startSeconds: Float,
    ) {
        // The ID ends up inside a JavaScript call on the player page, so anything
        // that is not a literal YouTube ID is dropped.
        if (!VIDEO_ID_PATTERN.matches(videoId)) return
        player.loadVideo(videoId, startSeconds)
    }
}

/**
 * Owns the views used by [YouTubePlayerComposable]: the [YouTubePlayerView] itself plus
 * the container it is hosted in, which doubles as the parent for the view the player
 * hands over when it enters fullscreen.
 */
private class YouTubePlayerHost(context: Context) {
    val container =
        FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

    val playerView: YouTubePlayerView =
        LayoutInflater
            .from(context)
            .inflate(R.layout.view_youtube_player, container, false) as YouTubePlayerView

    private var fullscreenView: View? = null
    private var released = false

    init {
        container.addView(playerView)
    }

    /**
     * Shows the view the player renders into while in fullscreen. Without adding it to a
     * container the video belongs to no window, which plays audio and draws nothing.
     */
    fun showFullscreenView(view: View) {
        hideFullscreenView()
        fullscreenView = view
        playerView.visibility = View.INVISIBLE
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Removes the fullscreen view and gives the inline player its surface back. */
    fun hideFullscreenView() {
        val view = fullscreenView ?: return
        fullscreenView = null
        container.removeView(view)
        playerView.visibility = View.VISIBLE
    }

    /** Releases the underlying WebView. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        hideFullscreenView()
        playerView.release()
    }
}

/**
 * Composable that embeds a YouTube player without the YouTube web UI, so the app's own
 * overlay controls stay the single way to drive playback (which keeps host authority
 * over guests intact).
 *
 * Playback runs through the `androidyoutubeplayer` library rather than a hand-rolled
 * WebView + IFrame API integration: the library owns the WebView's video surface,
 * fullscreen handoff and lifecycle, which is exactly the plumbing that made the
 * previous implementation play audio without ever drawing a picture.
 *
 * The [onPlayerReady] callback delivers a [YTPlayerController] once the player is
 * usable. State-change, position, duration, error and fullscreen events are forwarded
 * through the remaining callbacks so the caller can update its own UI state.
 */
@Composable
fun YouTubePlayerComposable(
    videoId: String,
    reloadNonce: Int,
    onPlayerReady: (YTPlayerController) -> Unit,
    onStateChange: (YTPlayerState) -> Unit,
    onError: (YTPlayerError) -> Unit,
    onCurrentSecond: (Float) -> Unit,
    onDuration: (Float) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The player listener is registered once, for the lifetime of the view, so it has
    // to read the callbacks through state holders to reach the current lambdas.
    val currentOnPlayerReady = rememberUpdatedState(onPlayerReady)
    val currentOnStateChange = rememberUpdatedState(onStateChange)
    val currentOnError = rememberUpdatedState(onError)
    val currentOnCurrentSecond = rememberUpdatedState(onCurrentSecond)
    val currentOnDuration = rememberUpdatedState(onDuration)
    val currentOnFullscreenChange = rememberUpdatedState(onFullscreenChange)

    var controller by remember { mutableStateOf<YTPlayerController?>(null) }
    var loadedRequestKey by remember { mutableStateOf("") }

    val host =
        remember(context) {
            YouTubePlayerHost(context).also { host ->
                host.playerView.addFullscreenListener(
                    object : FullscreenListener {
                        override fun onEnterFullscreen(
                            fullscreenView: View,
                            exitFullscreen: () -> Unit,
                        ) {
                            AppLogger.i(PLAYER_TAG, "player entered fullscreen")
                            host.showFullscreenView(fullscreenView)
                            currentOnFullscreenChange.value(true)
                        }

                        override fun onExitFullscreen() {
                            AppLogger.i(PLAYER_TAG, "player exited fullscreen")
                            host.hideFullscreenView()
                            currentOnFullscreenChange.value(false)
                        }
                    },
                )

                host.playerView.initialize(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            AppLogger.i(PLAYER_TAG, "player ready")
                            val readyController = LibraryYTPlayerController(youTubePlayer)
                            controller = readyController
                            currentOnPlayerReady.value(readyController)
                        }

                        override fun onStateChange(
                            youTubePlayer: YouTubePlayer,
                            state: PlayerConstants.PlayerState,
                        ) {
                            AppLogger.i(PLAYER_TAG, "state change $state")
                            currentOnStateChange.value(state.toYTPlayerState())
                        }

                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) {
                            AppLogger.e(PLAYER_TAG, "player error $error")
                            currentOnError.value(error.toYTPlayerError())
                        }

                        override fun onCurrentSecond(
                            youTubePlayer: YouTubePlayer,
                            second: Float,
                        ) {
                            AppLogger.v(PLAYER_TAG) { "currentTime=$second" }
                            currentOnCurrentSecond.value(second)
                        }

                        override fun onVideoDuration(
                            youTubePlayer: YouTubePlayer,
                            duration: Float,
                        ) {
                            AppLogger.v(PLAYER_TAG) { "duration=$duration" }
                            currentOnDuration.value(duration)
                        }
                    },
                    IFramePlayerOptions
                        .Builder(context)
                        // No YouTube web UI: the app draws its own controls and a guest
                        // must not be able to start playback behind the host's back.
                        .controls(0)
                        // Fullscreen is a layout decision owned by PlayerScreen, so the
                        // player's own fullscreen button stays hidden.
                        .fullscreen(0)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .origin(PLAYER_ORIGIN)
                        .build(),
                )
            }
        }

    // The view pauses playback when the host stops and releases the WebView when the
    // host is destroyed, so background audio never outlives the screen.
    DisposableEffect(lifecycleOwner, host) {
        lifecycleOwner.lifecycle.addObserver(host.playerView)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(host.playerView)
            host.release()
        }
    }

    // Load the requested video once the player is ready, and again whenever the video
    // or the retry nonce changes.
    LaunchedEffect(videoId, reloadNonce, controller) {
        val activeController = controller ?: return@LaunchedEffect
        if (!VIDEO_ID_PATTERN.matches(videoId)) return@LaunchedEffect

        val requestKey = "$videoId#$reloadNonce"
        if (requestKey == loadedRequestKey) return@LaunchedEffect

        AppLogger.i(PLAYER_TAG, "loading videoId=$videoId nonce=$reloadNonce")
        loadedRequestKey = requestKey
        activeController.loadVideo(videoId, 0f)
    }

    AndroidView(
        factory = { host.container },
        modifier = modifier,
    )
}
