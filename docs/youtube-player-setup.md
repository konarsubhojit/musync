# YouTube Player Setup

The in-app YouTube player is powered by
[`androidyoutubeplayer`](https://github.com/PierfrancescoSoffritti/android-youtube-player),
a maintained wrapper around the
[YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference).
The library owns the WebView that runs the IFrame API, including the parts that a
hand-rolled integration gets wrong on many devices: the video surface, the fullscreen
handoff, and the WebView lifecycle.
**No YouTube API key is required for playback** — the player talks directly
to YouTube's iFrame API.

---

## Requirements

| Requirement | Why |
|---|---|
| `android.permission.INTERNET` | Player fetches YouTube iFrame content over HTTPS |
| Up-to-date Android System WebView | The iFrame API relies on Chrome/WebView for JavaScript |
| Active internet connection | YouTube's servers must be reachable at play time |

All of these are already satisfied by the default app configuration.

---

## How it works

1. `YouTubePlayerComposable` hosts the library's `YouTubePlayerView` through Compose's
   `AndroidView`, in a container that also serves as the parent for the view the player
   hands over when it enters fullscreen. Nothing is served by the MuSync backend: the
   library ships its own player page.
2. The view is inflated from `res/layout/view_youtube_player.xml` with
   `app:enableAutomaticInitialization="false"`, because that flag is only readable from
   the XML attributes. It lets the composable call `initialize()` with its own
   `IFramePlayerOptions`: `controls(0)` (the app draws its own controls, so a guest
   cannot start playback behind the host's back), `fullscreen(0)`, `rel(0)`,
   `ivLoadPolicy(3)`, and an explicit `origin` of `https://www.youtube.com`.
3. The `origin` matters: the library loads its page with `loadDataWithBaseURL(origin, …)`,
   and YouTube's embed checks answer synthetic origins — such as the library default of
   `https://<package name>` — with the 150-series error even for embeddable videos.
4. Player events arrive through an `AbstractYouTubePlayerListener` (`onReady`,
   `onStateChange`, `onError`, `onCurrentSecond`, `onVideoDuration`) and are translated
   to the app's own `YTPlayerState` / `YTPlayerError` vocabulary, so the sync layer is
   unaware of which player implementation is in use.
5. `onReady` produces a `YTPlayerController` backed by the library's `YouTubePlayer`.
   The library marshals every command onto the main thread, so playback can be driven
   straight from socket callbacks.
6. `YouTubePlayerView` is registered as a lifecycle observer, which pauses playback when
   the host stops and releases the WebView when it is destroyed.

### Custom types

| Type | Description |
|---|---|
| `YTPlayerController` | Interface for `play()`, `pause()`, `seekTo()`, `loadVideo()` |
| `YTPlayerState` | Enum matching IFrame API state values (`PLAYING`, `PAUSED`, `BUFFERING`, `ENDED`, …) |
| `YTPlayerError` | Enum mapping IFrame API error codes to named constants |

---

## Common errors

| Error code | Meaning | Fix |
|---|---|---|
| `NOT_FOUND` (100) | Video not found or removed | Use a different video ID |
| `EMBEDDING_NOT_ALLOWED` (101/150) | Video owner disallows embedded playback | Use a video that permits embedding |
| `HTML5_ERROR` (5) | WebView cannot render the iFrame | Update Android System WebView; test on a different device/emulator |
| `INVALID_PARAMETER` (2) | Bad videoId or player parameter | Verify the video ID is a valid 11-character YouTube ID |
| `UNKNOWN` | Unexpected player error | Check internet connectivity; retry with a different video |
| Black screen, no error callback | WebView blocked by a VPN, firewall, or cleartext policy | Check connectivity; ensure YouTube HTTPS endpoints are reachable |
| Black screen but audio plays | The video surface is not composited: hardware acceleration disabled, or a fullscreen view the host never attached | Keep `android:hardwareAccelerated="true"` on the `<application>`; the library's `FullscreenListener` must stay wired up so the fullscreen view is added to a container; check the `YTPlayer` logs |

---

## Cleartext HTTP (local development)

The Android 9+ default network security policy blocks cleartext HTTP.
`android/src/main/res/xml/network_security_config.xml` relaxes this for
`10.0.2.2` (the Android emulator's host alias) and `localhost` so the
signalling server can be reached over plain HTTP during development.

YouTube's own traffic always uses HTTPS, so cleartext exceptions do **not**
affect the player.  When pointing `SERVER_URL` at a production HTTPS endpoint
the exception is never used.

---

## Lifecycle integration

`YouTubePlayerComposable` registers the `YouTubePlayerView` as a lifecycle observer, so the
player automatically:

- **pauses** when the host stops (`ON_STOP`)
- **resumes** the WebView when the host returns (`ON_RESUME`)
- **releases** the WebView when the host is destroyed, or when the composable leaves the
  composition

For background audio, MuSync uses `MediaPlaybackService` (a foreground
service) which bridges the notification controls to the YouTube player while
the app is in the background.  See
[`MediaPlaybackService.kt`](../android/src/main/kotlin/com/musync/playback/MediaPlaybackService.kt)
for details.

---

## Testing playback on an emulator

1. Start the server: `cd server && npm start`
2. Build and install the debug APK on an emulator with Google Play APIs
   (so WebView is up-to-date).
3. Create a room and enter any public YouTube video link or ID.
4. Confirm the player loads and plays.  If you see an `EMBEDDING_NOT_ALLOWED` error,
   the video owner has disabled embedded playback — try a different video.

> **Tip:** The Android emulator's WebView version can be outdated on first
> boot.  Run `adb shell am start -n com.google.android.webview/.SystemWebViewActivity`
> or update *Android System WebView* via the Play Store inside the emulator.
