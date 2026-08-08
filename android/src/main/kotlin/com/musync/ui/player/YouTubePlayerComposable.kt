package com.musync.ui.player

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * HTML page that bootstraps the YouTube IFrame Player API inside a [WebView].
 *
 * The page is loaded with a `youtube.com` base URL so that the IFrame API's
 * `postMessage` cross-origin checks pass.  All player commands (play, pause,
 * seekTo, loadVideo) are exposed as plain JavaScript functions called from the
 * Android side; all player events are forwarded to the `AndroidBridge`
 * JavaScript interface exposed by [YTAndroidBridge].
 */
private fun buildPlayerHtml(initialVideoId: String): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
        #player { width: 100%; height: 100%; }
      </style>
    </head>
    <body>
    <div id="player"></div>
    <script>
      var ytPlayer;
      var tag = document.createElement('script');
      tag.src = "https://www.youtube.com/iframe_api";
      document.head.appendChild(tag);

      function onYouTubeIframeAPIReady() {
        ytPlayer = new YT.Player('player', {
          width: '100%',
          height: '100%',
          videoId: '$initialVideoId',
          playerVars: {
            enablejsapi: 1,
            origin: 'https://www.youtube.com',
            widget_referrer: 'https://www.youtube.com',
            playsinline: 1,
            autoplay: 1,
            controls: 0,
            rel: 0,
            showinfo: 0,
            iv_load_policy: 3,
            modestbranding: 1
          },
          events: {
            onReady:       function(e) { AndroidBridge.onReady(); },
            onStateChange: function(e) { AndroidBridge.onStateChange(e.data); },
            onError:       function(e) { AndroidBridge.onError(e.data); }
          }
        });
      }

      function loadVideo(videoId, startSec) {
        if (ytPlayer && ytPlayer.loadVideoById) {
          ytPlayer.loadVideoById(videoId, startSec || 0);
        }
      }
      function playVideo()   { if (ytPlayer && ytPlayer.playVideo) ytPlayer.playVideo(); }
      function pauseVideo()  { if (ytPlayer && ytPlayer.pauseVideo) ytPlayer.pauseVideo(); }
      function seekTo(sec)   { if (ytPlayer && ytPlayer.seekTo) ytPlayer.seekTo(sec, true); }

      // Poll current-time & duration every 500 ms and forward to Android.
      setInterval(function() {
        try {
          if (ytPlayer && ytPlayer.getCurrentTime) {
            var curr = ytPlayer.getCurrentTime();
            var dur = ytPlayer.getDuration();
            if (curr !== undefined && curr !== null) AndroidBridge.onCurrentTime(curr);
            if (dur !== undefined && dur !== null) AndroidBridge.onDuration(dur);
          }
        } catch (ignored) {}
      }, 500);
    </script>
    </body>
    </html>
    """.trimIndent()

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
 * Composable that embeds a custom WebView-based YouTube player without native controls.
 * The player is powered directly by the YouTube IFrame Player API — no third-party
 * wrapper library is required.
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

                    // Remove WebView token from User-Agent so YouTube iframe player
                    // does not block playback with error 150/101 on physical Android devices.
                    val defaultUa = userAgentString ?: ""
                    userAgentString = defaultUa.replace("; wv", "").replace(" Version/4.0", "")
                    AppLogger.i(PLAYER_TAG, "WebView user-agent: $userAgentString")
                }
                wv.webChromeClient =
                    object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            // The iframe API reports embed/playback problems here; these
                            // messages are usually the only clue when the player stays blank.
                            AppLogger.i(
                                PLAYER_TAG,
                                "console [${message.messageLevel()}] ${message.message()} " +
                                    "(${message.sourceId()}:${message.lineNumber()})",
                            )
                            return true
                        }
                    }
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
            AppLogger.i(PLAYER_TAG, "loading player page for videoId=$videoId nonce=$reloadNonce")
            initialLoadedVideoId = videoId
            loadedRequestKey = requestKey
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                buildPlayerHtml(videoId),
                "text/html",
                "UTF-8",
                null,
            )
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
