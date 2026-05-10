# YouTube Data API v3 key setup

## Why this is needed

The server proxies:

- `GET /api/youtube/search`
- `GET /api/youtube/video-info/:videoId`

This keeps the API key on the server (never in the APK). If `YOUTUBE_API_KEY` is missing, both routes return HTTP `503` and the Android app's YouTube search and in-room playback queue flows fail.

## Generate a YouTube Data API v3 key

1. Open [https://console.cloud.google.com/](https://console.cloud.google.com/) and create (or select) a project.
2. In **APIs & Services → Library**, enable **YouTube Data API v3**.
3. Open **APIs & Services → Credentials → Create credentials → API key**.
4. Copy the generated key.
5. Recommended: click **Edit API key** and set restrictions:
   - **API restrictions**: restrict to **YouTube Data API v3** only.
   - **Application restrictions**: for server-side use, keep **None** or use **IP addresses** (production server IP).
   - Do **not** use **HTTP referrers** or **Android apps** restrictions for this key (the Node server uses it).
6. Quota: default free quota is **10,000 units/day**. Typical costs:
   - Search: `100` units/request
   - Video info: `1` unit/request
   - Monitor usage in **APIs & Services → Quotas**.

## Configure the server

### Local development

```bash
cd server
cp .env.example .env
# then edit .env and set:
# YOUTUBE_API_KEY=AIza...your_key...
npm install
npm start
```

### Production / hosted environments

- **systemd**: add `Environment=YOUTUBE_API_KEY=...` to your unit file.
- **Docker**: pass `-e YOUTUBE_API_KEY=...` or set it in compose under `environment:`.
- **Render / Railway / Fly.io / Heroku**: set `YOUTUBE_API_KEY` in the platform Environment / Secrets UI.

## Verification

Start the server and run:

```bash
curl -i "http://localhost:3000/api/youtube/search?q=lofi"
```

Expected results:

- `200` and JSON with an `items` array
- `503 {"error":"YouTube search not configured"}` → key not loaded by the server process
- `502 {"error":"YouTube API error"}` → key rejected by Google (check API enablement, restrictions, quota)
- `504 {"error":"YouTube API request timed out"}` → upstream timeout (tune `YOUTUBE_SEARCH_TIMEOUT_MS`)

Also verify the second endpoint:

```bash
curl -i "http://localhost:3000/api/youtube/video-info/jNQXAC9IVRw"
```

## Related environment variables

See `server/.env.example` for:

- `YOUTUBE_SEARCH_TIMEOUT_MS`
- `YOUTUBE_VIDEO_INFO_TIMEOUT_MS`
- `YOUTUBE_VIDEO_INFO_CACHE_SIZE`

## Security notes

- Never commit `.env`.
- Rotate the key immediately if leaked.
- Prefer IP restrictions for production server keys.

## Troubleshooting

| Symptom | Proxy HTTP status | What it usually means | Fix |
| --- | --- | --- | --- |
| Search button does nothing / search errors | `503` from `/api/youtube/search` | `YOUTUBE_API_KEY` missing in running server process | Set env var and restart server |
| Search fails after key setup | `502` from `/api/youtube/search` | Key rejected, API disabled, or quota/restrictions issue | Enable YouTube Data API v3, relax/fix restrictions, check quota |
| Add-to-queue metadata fetch fails | `502` from `/api/youtube/video-info/:videoId` | Same API key/restriction/quota issue | Same as above; test endpoint directly |
| Search/video info intermittently fail | `504` from either endpoint | Upstream timeout | Increase timeout env var(s), retry, check network |
| "Couldn't play this video" in room after queue add | Inspect `/api/youtube/video-info/:videoId` + socket logs | Metadata path may pass while playback/sync path fails | Check server/Android logs, room connectivity, and queue sync events |
