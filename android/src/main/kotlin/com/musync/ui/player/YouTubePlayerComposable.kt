package com.musync.ui.player

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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

/**
 * HTML page that bootstraps the YouTube IFrame Player API inside a [WebView].
 *
 * The page is loaded with a `youtube.com` base URL so that the IFrame API's
 * `postMessage` cross-origin checks pass.  All player commands (play, pause,
 * seekTo, loadVideo) are exposed as plain JavaScript functions called from the
 * Android side; all player events are forwarded to the `AndroidBridge`
 * JavaScript interface exposed by [YTAndroidBridge].
 */
private val PLAYER_HTML =
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
          videoId: '',
          playerVars: {
            playsinline: 1,
            autoplay: 0,
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
          ytPlayer.loadVideoById({ videoId: videoId, startSeconds: startSec });
        }
      }
      function playVideo()   { if (ytPlayer) ytPlayer.playVideo(); }
      function pauseVideo()  { if (ytPlayer) ytPlayer.pauseVideo(); }
      function seekTo(sec)   { if (ytPlayer) ytPlayer.seekTo(sec, true); }

      // Poll current-time & duration every 500 ms and forward to Android.
      setInterval(function() {
        try {
          if (ytPlayer && ytPlayer.getCurrentTime) {
            AndroidBridge.onCurrentTime(ytPlayer.getCurrentTime());
            AndroidBridge.onDuration(ytPlayer.getDuration());
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
    fun onReady() = onReady.invoke()

    @JavascriptInterface
    fun onStateChange(state: Int) = onStateChange(YTPlayerState.fromInt(state))

    @JavascriptInterface
    fun onError(code: Int) = onError(YTPlayerError.fromInt(code))

    @JavascriptInterface
    fun onCurrentTime(seconds: Double) = onCurrentTime(seconds.toFloat())

    @JavascriptInterface
    fun onDuration(seconds: Double) = onDuration(seconds.toFloat())
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

    val webView =
        remember(context) {
            WebView(context).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                wv.webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false
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

                // Load the player page using youtube.com as the base URL so that
                // the IFrame API's postMessage cross-origin checks pass.
                wv.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    PLAYER_HTML,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }

    // Pause/resume the WebView player with the Activity/Fragment lifecycle so
    // background audio is stopped when the user leaves the screen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    webView.evaluateJavascript("pauseVideo();", null)
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

    // Load (or reload) the video whenever videoId / reloadNonce / controller changes.
    LaunchedEffect(videoId, reloadNonce, controllerRef) {
        val controller = controllerRef
        val requestKey = "$videoId#$reloadNonce"
        if (videoId.isNotEmpty() && controller != null && requestKey != loadedRequestKey) {
            controller.loadVideo(videoId, 0f)
            loadedRequestKey = requestKey
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
