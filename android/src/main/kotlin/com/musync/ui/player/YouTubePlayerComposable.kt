package com.musync.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.musync.logging.AppLogger

/** Shared log tag for the WebView-hosted YouTube player. */
private const val PLAYER_TAG = "YTPlayer"

/** A literal YouTube video ID; anything else is unsafe to inject into the page. */
private val VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * JavaScript interface that forwards YouTube IFrame API events to Kotlin callbacks.
 * Registered under the name `"AndroidBridge"` on the [WebView].
 */
private class YTAndroidBridge(
    private val onReady: () -> Unit,
    private val onStateChange: (YTPlayerState) -> Unit,
    private val onError: (YTPlayerError) -> Unit,
    private val onCurrentTime: (Float) -> Unit,
    private val onDuration: (Float) -> Unit,
) {
    @JavascriptInterface
    fun onReady() {
        AppLogger.i(PLAYER_TAG, "iframe player ready")
        onReady.invoke()
    }

    @JavascriptInterface
    fun onStateChange(state: Int) {
        val mapped = YTPlayerState.fromInt(state)
        AppLogger.i(PLAYER_TAG, "state change raw=$state mapped=$mapped")
        onStateChange(mapped)
    }

    @JavascriptInterface
    fun onError(code: Int) {
        val mapped = YTPlayerError.fromInt(code)
        AppLogger.e(PLAYER_TAG, "iframe player error code=$code mapped=$mapped")
        onError(mapped)
    }

    @JavascriptInterface
    fun onCurrentTime(seconds: Double) {
        AppLogger.v(PLAYER_TAG) { "currentTime=$seconds" }
        onCurrentTime(seconds.toFloat())
    }

    @JavascriptInterface
    fun onDuration(seconds: Double) {
        AppLogger.v(PLAYER_TAG) { "duration=$seconds" }
        onDuration(seconds.toFloat())
    }
}

/**
 * [WebChromeClient] for the player WebView.
 *
 * Besides forwarding console output (the iframe API reports embed/playback problems
 * there), it attaches the fullscreen video view handed over by
 * [WebChromeClient.onShowCustomView] to the hosting Activity's content view. When the
 * host ignores that callback the video surface is never added to any window, so the
 * player area stays black while the audio track keeps playing.
 */
private class FullscreenAwareChromeClient(
    private val webView: WebView,
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(
        view: View,
        callback: CustomViewCallback?,
    ) {
        val container = webView.context.findActivity()?.findViewById<ViewGroup>(android.R.id.content)
        if (container == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        AppLogger.i(PLAYER_TAG, "entering HTML5 fullscreen video")
        customView = view
        customViewCallback = callback
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        AppLogger.i(PLAYER_TAG, "leaving HTML5 fullscreen video")
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        // These messages are usually the only clue when the player stays blank.
        AppLogger.i(
            PLAYER_TAG,
            "console [${message.messageLevel()}] ${message.message()} " +
                "(${message.sourceId()}:${message.lineNumber()})",
        )
        return true
    }
}

/** Walks the [ContextWrapper] chain to find the hosting [Activity], if any. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Composable that embeds a custom WebView-based YouTube player without native controls.
 * The player is powered directly by the YouTube IFrame Player API — no third-party
 * wrapper library is required.
 *
 * The page itself is served by the MuSync backend at `GET /player`. It is loaded over
 * real HTTP rather than injected with `loadDataWithBaseURL` so the iframe gets a
 * genuine origin and Referer; synthetic origins are rejected by YouTube's embed
 * checks with the 150-series error even for embeddable videos.
 *
 * The [onPlayerReady] callback delivers a [YTPlayerController] once the IFrame API
 * is ready.  State-change, position, and duration events are forwarded via the
 * remaining callbacks so the caller can update its own UI state.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerComposable(
    videoId: String,
    reloadNonce: Int,
    playerPageBaseUrl: String,
    onPlayerReady: (YTPlayerController) -> Unit,
    onStateChange: (YTPlayerState) -> Unit,
    onError: (YTPlayerError) -> Unit,
    onCurrentSecond: (Float) -> Unit,
    onDuration: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var controllerRef by remember { mutableStateOf<YTPlayerController?>(null) }
    var loadedRequestKey by remember { mutableStateOf("") }
    var initialLoadedVideoId by remember { mutableStateOf("") }

    val webView =
        remember(context) {
            WebView(context).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccess = true
                    allowContentAccess = true

                    // The User-Agent is deliberately left untouched. Stripping the "; wv"
                    // WebView token makes the client claim to be full Chrome, which no
                    // longer matches how the page actually behaves; YouTube's embed
                    // checks treat that mismatch as abuse and reject playback with the
                    // 150-series error on every video, including embeddable ones.
                    AppLogger.i(PLAYER_TAG, "WebView user-agent: $userAgentString")
                }
                // Video frames are composited on a hardware layer; on a software layer
                // the surface stays black while the audio track keeps playing.
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                wv.setBackgroundColor(Color.BLACK)
                wv.webChromeClient = FullscreenAwareChromeClient(wv)
                wv.webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            AppLogger.i(PLAYER_TAG, "page finished: $url")
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            AppLogger.e(
                                PLAYER_TAG,
                                "resource error ${error.errorCode} for ${request.url}: ${error.description}",
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            AppLogger.w(
                                PLAYER_TAG,
                                "http ${errorResponse.statusCode} for ${request.url}",
                            )
                        }
                    }

                val bridge =
                    YTAndroidBridge(
                        onReady = {
                            val controller =
                                object : YTPlayerController {
                                    override fun play() {
                                        wv.post { wv.evaluateJavascript("playVideo();", null) }
                                    }

                                    override fun pause() {
                                        wv.post { wv.evaluateJavascript("pauseVideo();", null) }
                                    }

                                    override fun seekTo(seconds: Float) {
                                        wv.post {
                                            wv.evaluateJavascript("seekTo($seconds);", null)
                                        }
                                    }

                                    override fun loadVideo(
                                        videoId: String,
                                        startSeconds: Float,
                                    ) {
                                        // The ID is interpolated into JavaScript running on a
                                        // youtube.com-origin page that has a live JS bridge, so
                                        // reject anything that is not a literal YouTube ID.
                                        if (!VIDEO_ID_PATTERN.matches(videoId)) return
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "loadVideo('$videoId', $startSeconds);",
                                                null,
                                            )
                                        }
                                    }
                                }
                            controllerRef = controller
                            onPlayerReady(controller)
                        },
                        onStateChange = onStateChange,
                        onError = onError,
                        onCurrentTime = onCurrentSecond,
                        onDuration = onDuration,
                    )

                wv.addJavascriptInterface(bridge, "AndroidBridge")
            }
        }

    // Pause/resume the WebView player with the Activity/Fragment lifecycle so
    // background audio is stopped when the user leaves the screen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    // The player page may not be loaded yet (e.g. the screen was left
                    // before a video was selected), in which case `pauseVideo` does not
                    // exist and a bare call raises a ReferenceError.
                    webView.evaluateJavascript(
                        "if (typeof pauseVideo === 'function') pauseVideo();",
                        null,
                    )
                    webView.onPause()
                }

                override fun onResume(owner: LifecycleOwner) {
                    webView.onResume()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.destroy()
        }
    }

    // Initialize or update the YouTube player page when videoId or reloadNonce changes.
    LaunchedEffect(videoId, reloadNonce, controllerRef) {
        if (!VIDEO_ID_PATTERN.matches(videoId)) return@LaunchedEffect

        val controller = controllerRef
        val requestKey = "$videoId#$reloadNonce"

        if (initialLoadedVideoId.isEmpty() || (reloadNonce > 0 && requestKey != loadedRequestKey && controller == null)) {
            // First load or explicit reload nonce when controller is reset: load full HTML template.
            val pageUrl = "${playerPageBaseUrl.trimEnd('/')}/player?videoId=$videoId"
            AppLogger.i(PLAYER_TAG, "loading player page $pageUrl nonce=$reloadNonce")
            initialLoadedVideoId = videoId
            loadedRequestKey = requestKey
            webView.loadUrl(pageUrl)
        } else if (controller != null && requestKey != loadedRequestKey) {
            // Player already initialized: load new video dynamically via JavaScript bridge.
            AppLogger.i(PLAYER_TAG, "switching to videoId=$videoId via bridge")
            controller.loadVideo(videoId, 0f)
            loadedRequestKey = requestKey
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
