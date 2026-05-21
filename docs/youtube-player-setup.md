# YouTube Player Setup

The in-app YouTube player is powered by a **custom WebView** implementation that loads the
[YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference)
directly — no third-party wrapper library is required.
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

1. `YouTubePlayerComposable` creates an Android `WebView` and loads a small
   inline HTML page that bootstraps the YouTube IFrame Player API.
2. The HTML page sets `youtube.com` as the `baseURL` (`loadDataWithBaseURL`) so that
   the IFrame API's `postMessage` cross-origin checks pass without needing an explicit
   `origin` parameter.
3. The JavaScript in the page communicates with the Android side via a
   `JavascriptInterface` (`AndroidBridge`).  This is how `onReady`, `onStateChange`,
   `onError`, and progress callbacks are delivered to Kotlin code.
4. The composable wrapper registers a `DefaultLifecycleObserver` so the WebView pauses
   and resumes automatically with the Android lifecycle.

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

`YouTubePlayerComposable` registers a `DefaultLifecycleObserver` so the player automatically:

- **pauses** when the Activity goes to the background (`onPause`)
- **resumes** the WebView when the Activity returns (`onResume`)
- **destroys** the WebView when the composable leaves the composition

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
