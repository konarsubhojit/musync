# musync

## Build-time configuration

Critical endpoints baked into the APK are configurable at build time via
Gradle properties (or matching environment variables).  When unset, the
defaults below — which target the local emulator-to-host setup — are used.

| Property                 | Default                                  | Purpose                                                                 |
| ------------------------ | ---------------------------------------- | ----------------------------------------------------------------------- |
| `SERVER_URL`             | `http://10.0.2.2:3000`                   | Signalling server base URL injected into `BuildConfig.SERVER_URL`.      |
| `INVITE_LINK_BASE_URL`   | `https://listen.yourdomain.com/room`     | Base URL used to build invite deep links. **Must include the `/room` path segment.** The app appends `/<roomId>` to produce the final link. |
| `INVITE_LINK_HOST`       | *(derived from `INVITE_LINK_BASE_URL`)*  | Bare hostname for the deep-link `<intent-filter>` in `AndroidManifest.xml`. Usually **does not need to be set** — it is extracted from `INVITE_LINK_BASE_URL` automatically. Must be a plain hostname only — no `https://` prefix, no port, no path. |

> **How the invite link is built:** `PlayerViewModel` constructs the invite link as
> `"$INVITE_LINK_BASE_URL/$roomId"` — for example `https://api.example.com/room/abc-123`.
> `INVITE_LINK_HOST` is **not** used for link construction; it only controls which
> host the Android OS intercepts to open the app directly.

Override locally:

```sh
./gradlew :android:assembleDebug \
  -PSERVER_URL=https://api.example.com \
  -PINVITE_LINK_BASE_URL=https://api.example.com/room \
  -PINVITE_LINK_HOST=api.example.com
```

### Common misconfigurations

| Mistake | Symptom | Correct value |
| --- | --- | --- |
| `INVITE_LINK_BASE_URL` missing `/room` (e.g. `https://host.example.com`) | Invite link is `https://host.example.com/<uuid>` — parser falls back to last path segment (resilient), but the server landing page and App Links verification won't match | `https://host.example.com/room` |
| `INVITE_LINK_HOST` set to a full URL (e.g. `https://host.example.com`) | `android:host` in the manifest becomes invalid — clicking a shared link opens the browser instead of the app | `host.example.com` (bare hostname only — **auto-derived, usually leave unset**) |
| `SERVER_URL` includes a non-standard port (e.g. `https://host.example.com:3000`) when using a reverse proxy like Render | All server requests fail — Render (and most hosts) terminate TLS on port 443 and the internal port is not exposed | `https://host.example.com` (no port) |

### CI configuration

The `Android CI` workflow forwards these values from repository
**Variables** (or **Secrets**) into the Gradle build via
`ORG_GRADLE_PROJECT_<NAME>` environment variables, so the debug APK
artifact uploaded by CI is a fully working build pointed at the configured
backend.

Configure them under
**Settings → Secrets and variables → Actions**:

- `vars.SERVER_URL`
- `vars.INVITE_LINK_BASE_URL`
- `vars.INVITE_LINK_HOST` *(optional — auto-derived from `INVITE_LINK_BASE_URL`)*

Use `secrets.*` (and reference them the same way in the workflow) for any
value that must not be exposed in logs.

### Deterministic debug signing (CI)

CI debug builds can be signed with a stable, known keystore so the
SHA-256 certificate fingerprint never changes between builds.  This is
required to use Android App Links verification (`autoVerify="true"`) with
a fixed `ANDROID_APP_SHA256_FINGERPRINTS` value on the server.

When the four secrets below are set in the repository, `assembleDebug`
uses the supplied keystore instead of the default per-machine Android debug
key.  When they are absent (e.g. on a fork or a local developer machine)
the build falls back to the normal Android debug keystore so nothing breaks.

Configure all four secrets under **Settings → Secrets and variables → Actions**:

| Secret                   | Description                                                    |
| ------------------------ | -------------------------------------------------------------- |
| `DEBUG_KEYSTORE_BASE64`  | Base64-encoded `.jks` / `.keystore` file (`base64 debug.jks` on Linux/macOS) |
| `DEBUG_KEYSTORE_PASSWORD`| Keystore store password                                        |
| `DEBUG_KEY_ALIAS`        | Key alias inside the keystore                                  |
| `DEBUG_KEY_PASSWORD`     | Key password                                                   |

Once the keystore is in place, retrieve the SHA-256 fingerprint with:

```sh
keytool -list -v -keystore debug.jks -alias <alias>
```

Set that fingerprint (and your release fingerprint if applicable) as
`ANDROID_APP_SHA256_FINGERPRINTS` in the server environment (see
`server/.env.example`).

**Notes:**
- `ANDROID_APP_PACKAGE_NAME` stays `com.musync`.
- Release signing is **not** affected by these variables; configure release
  signing separately when you prepare a production build.
- Local developers do not need to set any of these variables.

### Using the same host for the API and invite links

`SERVER_URL` and `INVITE_LINK_BASE_URL` can point to **the same** Node.js
deployment.  The server exposes the two routes Android needs for the invite
flow to work end-to-end:

- `GET /room/:roomId` — minimal HTML landing page rendered when a user opens
  the invite link in a browser (or before App Links verification kicks in).
- `GET /.well-known/assetlinks.json` — Android App Links manifest, populated
  from `ANDROID_APP_PACKAGE_NAME` and `ANDROID_APP_SHA256_FINGERPRINTS`
  (see `server/.env.example`).  This is what makes `android:autoVerify="true"`
  succeed so shared links open straight in the app.

For example, with everything served from `https://api.example.com`:

| Variable               | Value                                  | Notes |
| ---------------------- | -------------------------------------- | ----- |
| `SERVER_URL`           | `https://api.example.com`              | No trailing slash, no port (use 443) |
| `INVITE_LINK_BASE_URL` | `https://api.example.com/room`         | Must end in `/room` |
| `INVITE_LINK_HOST`     | `api.example.com`                      | Optional — auto-derived from `INVITE_LINK_BASE_URL` |

The invite link the app shares will be `https://api.example.com/room/<roomId>`.
When a recipient taps it, the OS matches `https://api.example.com/room/*` against
the `<intent-filter>` and opens the app directly (once App Links is verified).

