# YouTube Player Setup

The in-app YouTube player is powered by the
[android-youtube-player](https://github.com/PierfrancescosoftFritti/android-youtube-player)
library (v13.0.0), which embeds a YouTube iFrame inside an Android WebView.
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

1. `YouTubePlayerView` (a subclass of `FrameLayout`) creates a `WebView` that
   loads a small HTML page bundled inside the library.
2. The HTML page embeds the YouTube iFrame Player with `enablejsapi=1` and, optionally,
   an `origin` parameter (see below).
3. The JavaScript in the page communicates with the Android side via a
   `JavascriptInterface`.  This is how `onReady`, `onStateChange`, `onError`,
   and progress callbacks are delivered to Kotlin code.
4. The composable wrapper (`YouTubePlayerComposable`) registers the view as a
   `LifecycleObserver` so the embedded player pauses and resumes automatically
   with the Android lifecycle.

---

## The `origin` parameter

The IFrame Player API accepts an optional `origin` query parameter that
restricts which page can communicate with the player via
[postMessage](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage).

The current configuration sets:

```kotlin
IFramePlayerOptions.Builder()
    .controls(0)   // hide native controls — custom overlay is used instead
    .rel(0)        // suppress "related videos" at end of playback
    .fullscreen(0) // fullscreen handled by the app, not the iFrame
    .origin("https://www.youtube-nocookie.com")
    .build()
```

Setting `origin` is needed to prevent errors **152** and **153** (`UNKNOWN`) that some
devices report when the iFrame cannot establish a trusted communication
channel with the host page.  Using `"https://www.youtube-nocookie.com"` is the
recommended workaround for WebView-based embeds where the host page origin
is `file://` or `null`.  The `youtube-nocookie.com` domain is YouTube's
privacy-enhanced embed endpoint and is also accepted by the IFrame API as a
valid `postMessage` target, fixing the error 153 variants seen on newer
Android WebView versions.

---

## Common errors

| Error code | Meaning | Fix |
|---|---|---|
| `100` | Video not found or removed | Use a different video ID |
| `101` / `150` | Video owner disallows embedded playback | Use a video that permits embedding |
| `152` / `UNKNOWN` | iFrame communication failure | Already mitigated by the `origin` parameter above; ensure internet access |
| `153` / `UNKNOWN` | iFrame `postMessage` rejected by newer WebView | Already mitigated by switching `origin` to `https://www.youtube-nocookie.com` |
| `HTML_5_PLAYER_ERROR` (5) | WebView cannot render the iFrame | Update Android System WebView; test on a different device/emulator |
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

`YouTubePlayerComposable` registers the `YouTubePlayerView` as a lifecycle
observer (`lifecycle.addObserver(view)`) so the player automatically:

- **pauses** when the Activity goes to the background
- **resumes** when the Activity returns to the foreground
- **releases** its WebView when the composable leaves the composition

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
4. Confirm the player loads and plays.  If you see error 152, ensure the
   device has internet access and try a different video.

> **Tip:** The Android emulator's WebView version can be outdated on first
> boot.  Run `adb shell am start -n com.google.android.webview/.SystemWebViewActivity`
> or update *Android System WebView* via the Play Store inside the emulator.
