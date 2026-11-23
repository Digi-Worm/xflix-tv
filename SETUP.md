# Working on Xflix TV from a fresh clone

This is the loop to use every session: pull, build, test on the TV, archive the
APK, push. Nothing here needs the old local machine state.

Repository: `https://github.com/Digi-Worm/xflix-tv` (private)

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | `JAVA_HOME` must point at it |
| Android SDK | platform 34, build-tools 34.0.0 | `ANDROID_HOME` set |
| Git | any recent | |

Gradle is **not** installed globally. The wrapper (`gradlew.bat`) pins the correct
version and downloads it on first run.

`local.properties` is intentionally not in the repo (it holds a machine-specific
SDK path). Create it once per machine:

```
sdk.dir=C\:\\Android\\Sdk
```

Or just set `ANDROID_HOME=C:\Android\Sdk` and skip the file.

---

## 2. Clone and build

```
git clone https://github.com/Digi-Worm/xflix-tv.git
cd xflix-tv
.\gradlew.bat :app:assembleDebug --offline
```

- Output: `app/build/outputs/apk/debug/app-debug.apk`
- PowerShell prints a misleading `Exit code: 1` even on success. Trust the
  `BUILD SUCCESSFUL` line, not the exit code.
- `--offline` works after the first build has populated the Gradle cache.
  Drop it on a fresh machine so dependencies can download.

---

## 3. Install on the TV

The TV is reached over ADB on the LAN (port 5555 is enabled in TV developer
options).

```
adb connect 192.168.1.111:5555
adb -s 192.168.1.111:5555 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 192.168.1.111:5555 shell am start -n dev.digi.xflixtv/.MainActivity
```

Package id: `dev.digi.xflixtv`

### TV specifics that cost time if forgotten

- **Always pass `-s 192.168.1.111:5555`.** An emulator is often also attached
  (`emulator-5554`) and will silently receive the command otherwise.
- The TV is a 3840x2160 panel overridden to 1920x1080 at `density 320`, so the
  Compose viewport is only **960x540 dp**. Size layouts from the viewport, never
  from fixed dp, or everything renders double size.
- **`adb screencap` returns black** on this TV. To inspect UI, dump the view
  tree instead and read it as UTF-8:
  ```
  adb -s 192.168.1.111:5555 shell uiautomator dump /sdcard/x.xml
  adb -s 192.168.1.111:5555 pull /sdcard/x.xml .
  ```
- `uiautomator dump` fails with "null root node" while the player surface is
  playing with the overlay hidden, and intermittently right after launch. Press
  OK first, or retry after 10-15 s.
- **Do not run `wm size 1280x720`.** Android TV rejects it with a "does not
  support display resolution" system dialog. Reset with `wm size reset`.
- A preinstalled vendor streaming app can steal the foreground and invalidate
  measurements. Force-stop it before testing.
- TCL's own player (`com.tcl.tv`) can also take over when OK is pressed inside
  a playing stream.
- D-pad keyevents are flaky. Prefer `input tap x y` using coordinates taken from
  a `uiautomator` bounds dump. Tapping the *text* node of a Play button often
  does nothing - tap the clickable parent's bounds, or focus it and send
  `keyevent 23`.
- Text glyphs such as `▶`, `⌕`, `×`, `·` render as `?` on this panel. Use
  vector icons from `material-icons-extended` or plain words.

---

## 4. Archive the tested build

Only archive a build you have actually verified on the TV.

```
.\backup-apk.ps1
```

This copies the debug APK to `backups\apk\Xflix-TV-v<name>-b<code>.apk` and keeps
only the four newest, so a stale build can never be reinstalled by accident.

Then publish it into the repo (one APK only, always the current tested one):

```
copy /Y backups\apk\Xflix-TV-v1.5.1-b7.apk release\
```

`release/*.apk` is the single exception to the `*.apk` ignore rule. Delete the
previous APK from `release/` in the same commit so the folder never accumulates.

---

## 5. Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = 8          // always +1
versionName = "1.5.2"    // semantic
```

`versionCode` must strictly increase or the TV will refuse the downgrade/upgrade
depending on direction.

---

## 6. Push

```
git add -A
git commit -m "Xflix TV v1.5.2 (versionCode 8): <what changed>"
git push origin main
```

Practical commit-message rule: state the **user-visible symptom** that was fixed
and the **measurement** that proves it. Future debugging depends on those numbers
more than on the prose.

---

## What is in the repo and what is not

Tracked:

```
app/src/main/java/dev/digi/xflixtv/**   19 Kotlin files
app/src/main/res/**                        icons, theme, strings
app/src/main/AndroidManifest.xml
app/build.gradle.kts
build.gradle.kts, settings.gradle.kts, gradle.properties
gradle/wrapper/**                          wrapper jar + properties
gradlew.bat
backup-apk.ps1
release/Xflix-TV-v<ver>-b<code>.apk        the current tested build
README.md, SETUP.md, .gitignore
```

Not tracked, on purpose:

| Path | Why |
|---|---|
| `ref/` | official APK plus jadx/apktool output, ~1099 MB. Over GitHub's 100 MB per-file limit and not needed to build. Kept locally as UI reference. |
| `tools/` | jadx, apktool, libtorrent4j jars, ~256 MB. Same reason. |
| `backups/` | rolling local APK archive (4 newest). |
| `app/build/`, `.gradle/`, `.kotlin/` | regenerated by Gradle. |
| `local.properties` | machine-specific SDK path. |

Because `ref/` and `tools/` are ignored rather than deleted, builds still work
locally without them, and they remain available if UI measurements need to be
re-checked against the official build. They just never reach GitHub.
