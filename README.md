# Digi X TV

A native Android TV streaming client. Compose for TV UI, Media3/ExoPlayer
playback, embedded libtorrent for `infoHash` streams, local persistence.
**No login, no account, no server.**

**Developed by Digi**

---

## What it does

Digi X TV is a self-contained player for open addon-protocol catalogs. It talks
directly to addon HTTP endpoints and renders the results in a leanback UI:

```
GET  {addon}/manifest.json
GET  {addon}/catalog/{type}/{id}/{extra}.json   -> { "metas": [...] }
GET  {addon}/meta/{type}/{id}.json              -> { "meta": {...} }
GET  {addon}/stream/{type}/{id}.json            -> { "streams": [...] }
GET  {addon}/subtitles/{type}/{id}.json         -> { "subtitles": [...] }
```

**v2.1.0 features:**
- Native Android TV UI with Jetpack Compose for TV
- Built-in libtorrent engine for `infoHash` streaming
- Full LAN casting (mDNS/NSD + HTTP endpoints)
- Phone/tablet compatibility (auto-rotates)
- Compact two-row player deck (~15% of frame)

`extra` is a URL-encoded path segment, not a query string: `search=foo&skip=10`.

Catalogs and metadata come from whatever addons the user installs. Streams are
either direct HTTP URLs or `infoHash` torrents, which are resolved locally by an
embedded BitTorrent engine and served to the player over a local HTTP range
server. Nothing is proxied through a remote service.

---

## Architecture

| Layer | File | Responsibility |
|---|---|---|
| Entry | `MainActivity.kt` | Deep links (`digix://detail/...`, `digix://stream/...`) |
| App | `DigiXApp.kt` | Process-wide singletons, storage sweep scheduling |
| Protocol | `data/AddonProtocol.kt` | Manifest/meta/stream models, stream scoring |
| Transport | `data/AddonClient.kt` | Addon HTTP calls, manifest resolution |
| Torrents | `data/TorrentEngine.kt` | libtorrent session, local range server |
| Storage | `data/StorageJanitor.kt` | Cache and payload sweeping |
| State | `ui/MainViewModel.kt` | Catalog, detail, stream and playback state |
| Player | `ui/player/PlayerScreen.kt` | ExoPlayer surface, controls, failover |

---

## Playback design notes

Three behaviours matter more than they look:

**1. Never trust a pre-allocated file.**
Torrent clients can pre-allocate the full file size as sparse zeros. If a
payload is trusted without verification, the player receives hundreds of
megabytes of `0x00`, fails with `UnrecognizedInputFormatException`, retries
another source, and loops forever. This build probes the file's leading bytes
for a real container magic number, forces a recheck when they are zero, and the
range server refuses to serve an all-zero head.

**2. Park, don't pause.**
A paused torrent can be resumed by the engine's own auto-management, silently
halving the bandwidth available to the stream the user is actually watching.
Parking clears the auto-managed flag before pausing, so an abandoned source
stays abandoned. Verified: a parked payload directory stayed frozen at 4636 KiB
across 45 s with zero growth.

**3. Protect the live payload.**
The storage sweeper must never delete the file currently being streamed. The
janitor takes an explicit pin on the active payload directory and skips it.

Stream selection ranks 10-bit HEVC last: on some TV decoders it produces no
frames at all. All failover paths share one ordering function so a retry cannot
land on a source the first pass already rejected.

---

## Building

Requires JDK 17 and Android SDK platform 34+.

```
.\gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

PowerShell prints a misleading `Exit code: 1` even on success. Trust the
`BUILD SUCCESSFUL` line.

---

## Automatic updates

The app reads `update.json` from the repo once per day on launch (via the GitHub
contents API — `raw.githubusercontent.com` caches for minutes and ignores
query-string cache busting) and compares `versionCode` with the installed one.
When a newer version exists it posts a notification with a **Download** action.

There is also a **manual path**: *Settings → Check for updates*. It states the
current status ("You are on the latest version", "Version X is available") and
the button switches between **Check now** and **Update now**, which opens
`DownloadUpdateActivity` and hands the APK to the system installer.

**The repository must be public**, or the app gets 404 and never sees an update.

To publish an update:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, build.
2. Copy the APK into `release/DigiX-TV-v<version>-b<code>.apk` and commit it.
3. Edit `update.json` so `versionCode`, `versionName` and `apkUrl` match.
4. `git push` — every install picks it up within a day (or on next launch).

Android forbids silent self-updates, so the final step is always the system's own
"update this app?" prompt. The APK must also be signed with the same key as the
installed build, and users must allow "install unknown apps" for Digi X TV.

---

## Layout reference

The UI was measured against a leanback reference build for spacing and palette
only — no code was reused:

| Measured | Used here |
|---|---|
| side rail width | 85dp |
| poster card | 125dp, 10dp gaps |
| background / surface | `#0C0C10` / `#18181C` |
| accent | `#E50914` |

Everything is sized from the viewport rather than fixed dp, because TV panels
vary widely in reported density.

---

## Requirements

- Android TV / Google TV / Phones / Tablets, API 24+
- `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
- No Google account, no network login
- WiFi connection required for casting
