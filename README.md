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
- Full LAN casting (active subnet scan + Cast setup scan + mDNS/NSD + HTTP endpoints)
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
query-string cache busting) and compares `versionCode` with the version actually
installed, read from `PackageManager`. When a newer version exists it posts a
notification with a **Download** action.

There is also a **manual path**: *Settings → Check for updates*. It states the
current status ("You are on the latest version", "Version X is available") and
the button switches between **Check now** and **Update now**, which opens
`DownloadUpdateActivity` and hands the APK to the system installer.

The manual button always bypasses the once-a-day gate, so a freshly pushed
`update.json` is visible on the next tap. Notifications additionally require the
Android 13+ `POST_NOTIFICATIONS` runtime grant, which the app requests on first
launch; if it is denied, the Settings row is still authoritative.

**The repository must be public**, or the app gets 404 and never sees an update.

To publish an update:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, build.
2. Copy the APK into `release/DigiX-TV-v<version>-b<code>.apk` and commit it.
3. Edit `update.json` so `versionCode`, `versionName` and `apkUrl` match.
4. `git push` — every install picks it up within a day (or on next launch).

Android forbids silent self-updates, so the final step is always the system's own
"update this app?" prompt. The APK must also be signed with the same key as the
installed build, and users must allow "install unknown apps" for Digi X TV.

### Why an update could "do nothing"

`DownloadManager` never overwrites an existing destination — it saves alongside it
as `name-1.apk`, `name-2.apk`, and so on. The old code always installed the
hard-coded `DigiX-TV-update.apk`, so a second update reinstalled the *first*
download and the app stayed on the old build no matter how many times you tapped.

`DownloadUpdateActivity` now:

- names each download `DigiX-TV-update-<versionCode>.apk`, so builds cannot collide;
- deletes leftover `DigiX-TV-update*.apk` files before downloading;
- installs the file path returned by `DownloadManager` (`COLUMN_LOCAL_URI`) instead
  of assuming a name;
- reads the downloaded APK with `getPackageArchiveInfo` and refuses to install it
  unless its `versionCode` matches the one advertised in `update.json`, deleting
  the file and reporting "Downloaded build N instead of M" if it does not.

That last check turns a silent no-op into a visible error, which is what would
have caught the original bug instead of leaving you on the old version.

`StorageJanitor` also removes `DigiX-TV-update*.apk` files older than ten minutes
from the app's Downloads folder on every start and in the periodic sweep. Older
builds left one ~46 MB installer per update — six of them on the dev TV — and the
oldest was a build several versions behind, which is what the installer picked up
and why Android reported a downgrade as an invalid package.

### What casting does and does not do

Two protocols are supported, and the picker marks which one each device uses.

**Discovery does not depend on mDNS.** This is the fix for "the phone can't see
any TV". mDNS is the documented mechanism, but it is also the least reliable
part of Android networking: many access points filter multicast, some Wi-Fi
drivers disable it in power save, and `NsdManager` is missing or broken on a
fair number of phones. Discovery therefore runs three probes concurrently:

| Path | Port | Endpoint | Catches |
|---|---|---|---|
| Digi X TV scan | 47823 | `GET /health` | our own receiver, by address |
| Cast setup scan | 8008 | `GET /setup/eureka_info` | every Chromecast / Cast TV, by address |
| mDNS / NSD | — | `_digixtv._tcp.`, `_googlecast._tcp.` | cross-subnet, and device display names |

The two address scans are what make it behave like any other casting app: they
need no multicast at all, so a phone whose mDNS is dead still sees the TV. The
Cast probe gets its own 1.2 s budget rather than the 400 ms used for `/health`,
because a Cast receiver's embedded web server answers slower and the tighter
window was making real TVs intermittently absent.

**Digi X TV** (`_digixtv._tcp.`, port 47823) — the phone sends a stream
*descriptor*; the TV resolves it itself through its own torrent engine, so
playback continues after the phone leaves the room or locks.

**Chromecast / Google Cast** (`_googlecast._tcp.`, port 8009) — implemented
natively in `ChromecastClient`, with no Play Services dependency and no
registered receiver app ID. It speaks Cast v2 over TLS: a 4-byte length-prefixed
`CastMessage` protobuf, `LAUNCH` of Google's Default Media Receiver (`CC1AD845`),
then `LOAD` on the returned `transportId`. Verified against real hardware.

A Chromecast is a *dumb player*: it fetches the media URL itself and can only
decode MP4 and WebM (no Matroska, which is most torrent releases). Two
consequences, both surfaced in the UI rather than failing silently:

- A source with no direct `url` (pure torrent) is rejected with an explanation.
- An `.mkv`/`.avi`/`.wmv`/`.flv` URL is rejected before the connection opens,
  because the receiver would otherwise sit on a black screen.

`play()` waits for the receiver's verdict and treats `LOAD_FAILED` or an `IDLE`
state with an `idleReason` as failure. A well-formed `LOAD` is always *accepted*
by the device before it discovers it cannot play the media, so returning on send
would have reported broken casts as successful.

**AirPlay is not supported.** There is no public sender protocol or SDK.

### Why the Play button did nothing on a phone

Two independent faults stacked up, and either one alone produced the same
symptom.

**Wrong button type.** Play, "More Info" and "My List" were
`androidx.tv.material3.Button`, and the stream rows were `androidx.tv.material3.Card`.
Those components activate from a D-pad `KEYCODE_DPAD_CENTER` key event; they have
no `clickable` touch handling of their own. On a phone the button rendered
correctly and highlighted on press, but the tap went nowhere. They now all use
`ActionPill` (a `Box` with `clickable`), which handles touch *and* still responds
to the D-pad centre key when focused, so TV behaviour is preserved rather than
traded away.

**Clipped hit box.** The hero was `Box(height = backdropHeight)` inside a
`LazyColumn` item. Inside a lazy item the incoming max height is infinite, so
`maxHeight * 0.5f` was `Infinity` and `coerceIn` pinned it to the 420dp ceiling
on every device. The title, wrapped chips, four-line synopsis and the action row
are taller than that in portrait, and the row is `align(BottomStart)`, so it grew
*upward out of* the Box. A parent does not hit-test children drawn outside its
bounds — the button was visible and unclickable. The hero now uses
`heightIn(min = …)`, sized from the configuration's screen height instead of the
infinite parent, so it grows to fit its content.

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
- Phone and TV must be on the same network. The phone finds TVs three ways at
  once: a direct scan of the local /24 on the cast port (`GET /health`, results
  in ~2 s), a direct scan of the same /24 on **port 8008** (`GET
  /setup/eureka_info`, which every Google Cast device serves), and mDNS/NSD,
  which also covers the case where the TV sits on a different subnet and the
  router lets multicast through. Manual address entry remains the fallback for
  isolated guest networks, and now recognises both Digi X TVs and Cast devices.
