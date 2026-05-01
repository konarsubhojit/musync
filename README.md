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
