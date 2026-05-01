# musync

## Build-time configuration

Critical endpoints baked into the APK are configurable at build time via
Gradle properties (or matching environment variables).  When unset, the
defaults below — which target the local emulator-to-host setup — are used.

| Property                 | Default                                  | Purpose                                                                 |
| ------------------------ | ---------------------------------------- | ----------------------------------------------------------------------- |
| `SERVER_URL`             | `http://10.0.2.2:3000`                   | Signalling server base URL injected into `BuildConfig.SERVER_URL`.      |
| `INVITE_LINK_BASE_URL`   | `https://listen.yourdomain.com/room`     | Base URL used to build invite deep links.                               |
| `INVITE_LINK_HOST`       | `listen.yourdomain.com`                  | Host applied to the deep-link `<intent-filter>` in `AndroidManifest.xml`. |

Override locally:

```sh
./gradlew :android:assembleDebug \
  -PSERVER_URL=https://api.example.com \
  -PINVITE_LINK_BASE_URL=https://listen.example.com/room \
  -PINVITE_LINK_HOST=listen.example.com
```

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
- `vars.INVITE_LINK_HOST`

Use `secrets.*` (and reference them the same way in the workflow) for any
value that must not be exposed in logs.

### Using the same host for the API and invite links

`SERVER_URL` and `INVITE_LINK_HOST` can point to **the same** Node.js
deployment.  The server exposes the two routes Android needs for the invite
flow to work end-to-end:

- `GET /room/:roomId` — minimal HTML landing page rendered when a user opens
  the invite link in a browser (or before App Links verification kicks in).
- `GET /.well-known/assetlinks.json` — Android App Links manifest, populated
  from `ANDROID_APP_PACKAGE_NAME` and `ANDROID_APP_SHA256_FINGERPRINTS`
  (see `server/.env.example`).  This is what makes `android:autoVerify="true"`
  succeed so shared links open straight in the app.

For example, with everything served from `https://api.example.com`:

| Variable               | Value                                  |
| ---------------------- | -------------------------------------- |
| `SERVER_URL`           | `https://api.example.com`              |
| `INVITE_LINK_BASE_URL` | `https://api.example.com/room`         |
| `INVITE_LINK_HOST`     | `api.example.com`                      |

