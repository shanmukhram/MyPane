# Pane

A home launcher for a 2 GB Android TV (built for a Xiaomi Mi TV, Android 10, 1080p).
Glass look, spring motion, no dependencies. The release APK is about 50 KB.

## What it does

- **Home:** wallpaper, a time/date capsule, top buttons (network, search, launcher
  settings, system settings) and a glass dock with up to six apps.
- **Apps:** the page continues below the dock. The next row peeks in through a dark
  fade; Down (or the round apps button) glides into the full grid, Up or Back returns.
- **TV inputs:** the round inputs button lists HDMI/AV/tuner inputs from
  `TvInputManager` and hands off to the TV's own live-TV app.
- **Long-press an app** (or press Menu): open, add to / remove from dock, move, hide,
  app info, uninstall.
- **Search:** press Search, or just start typing on a keyboard or phone remote.
- **Wallpaper:** eight built-in gradients, or any photo in the TV's Pictures or
  Download folder or on a USB drive.
- **Pane settings:** wallpaper, reduce motion, hidden apps, backup / restore
  (`Download/pane-backup.json`), accessibility settings shortcut.
- New installs appear automatically; removed apps disappear.

## Build and install

Needs JDK 17, the Android SDK (platform 34, build-tools 34) and Gradle 8.9.

```bash
gradle :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity tv.pane.launcher/.MainActivity
```

## Tech decision: Kotlin and plain Views, no Rust, no Compose

Measured on the TV, a frame costs under 1 ms on the app's own thread and 4 to 8 ms
issuing drawing to the GPU. The language the app logic is written in is not where
the time goes, so Rust would not make it faster. A custom-rendered Rust UI would
also have to rebuild focus handling and TalkBack support that Views give for free.

Compose was ruled out by the baseline: the Compose launcher this replaces idles at
113 to 118 MB with 58 MB of graphics memory. Plain Views with one custom-drawn View
per tile idle at about a quarter of that.

No Rust/NDK hot path turned up. The one CPU-heavy job, blurring the wallpaper, runs
once per wallpaper change on a 240x135 bitmap and takes milliseconds.

## How it stays light and smooth

- **No live blur.** API 29 has no `RenderEffect`. The wallpaper is blurred once,
  cached on disk, and each glass panel draws the matching crop of a pre-tinted copy
  in a single pass.
- **Springs run on `Choreographer`,** not `ValueAnimator`, so they ignore the system
  animator scale (which is 0 on this TV) and only ever change transform and alpha.
- **One View per tile,** drawing its own artwork and label. Artwork is rendered at
  display size, held as GPU-only bitmaps in a 6 MB LRU, cached on disk, and only
  attached to tiles on or near the screen. Everything is dropped when Home goes to
  the background.
- **No full-screen fades or zooms.** They measured 45 to 100% late frames on this
  GPU, so the apps view is a scroll of one page rather than a panel that opens.
- No services, no network permission, no analytics.

## Test hook

`adb shell am start -n tv.pane.launcher/.MainActivity --ei stress 150` pads the grid
with 150 clone tiles (each with its own artwork) for memory testing; `--ei stress 0`
turns it off.

## Measured on the TV

Idle is 15 s after pressing Home. Late frames are from `dumpsys gfxinfo` while
navigating with the D-pad. The launcher it replaced was a Jetpack Compose launcher.

| | Pane | Compose launcher | Target |
|---|---|---|---|
| Idle memory (PSS) | 30.7 MB | 71 to 118 MB | 40 MB or less |
| Idle graphics memory | 12.0 MB | 35 to 58 MB | 25 MB or less |
| Late frames while navigating | 0.3% | 100% | under 5% |
| Median frame time | 7 ms | 34 ms | 16.7 ms or less |
| Cold start (AOT-compiled) | 420 to 452 ms | 454 to 469 ms | |
| APK size | 53 KB | | |

With 166 tiles (16 real apps plus 150 clones) memory held at 41.7 to 42.1 MB across
ten rounds of app switch plus a full scroll, with no upward drift.

Right after `adb install` the app runs uncompiled and cold start is about 0.9 s.
Android compiles it in the background later, or force it with
`adb shell cmd package compile -m speed -f tv.pane.launcher`.
