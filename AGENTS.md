# Notes for contributors and coding agents

Luch is a low-latency screen receiver for Android TV and projectors (Android 5.0+, one APK). The sender is a separate project,
[luch-linux](https://github.com/Toxblh/luch-linux). The wire protocol is in [PROTOCOL.md](PROTOCOL.md): change both
sides together.

## Layout

- `android/app/src/main/java/ru/toxblh/luch/`: the receiver, plain Java, no dependencies in the APK.
  - `LuchService`: the always-on service; `ControlServer`: the TCP control port 47800.
  - `MirrorSession`: one session (UDP video and audio, decoding); `MirrorActivity`: its screen.
  - `LowLatencyDecoder`: decoder setup; `Bench` and `Caps`: what the TV can do, sent in the welcome.
  - `Reports`: crash and error reports kept on the TV and handed to a sender that asks (PROTOCOL.md, section 6).
    No server and no logs; keep addresses and names out of anything added to a report.
- `android/app/src/androidTest/`: on-device tests; `MirrorStartTest` plays the sender over localhost.
- `tools/testsend.py`: a test sender (ffmpeg's test pattern) for checking a receiver by hand.

## Build and test

- JDK 17 or 21 (Android Studio's `jbr` works). Gradle 8.9 does not run on newer JDKs.
- Three builds (product flavors): `github` updates itself from GitHub releases, `fdroid` and `play` never do
  (`BuildConfig.SELF_UPDATE`; the install permissions are only in `src/github`). See [docs/updates.md](docs/updates.md).
- `cd android && ./gradlew assembleFdroidRelease`: unsigned, as F-Droid builds it; `assembleGithubRelease
  -PluchDebugSign` signs with the debug key.
- `ANDROID_SERIAL=<emulator> ./gradlew connectedGithubDebugAndroidTest` on an Android TV emulator (API 34
  `android-tv;arm64-v8a` works on Apple Silicon). **Always set `ANDROID_SERIAL`**: Gradle otherwise runs the tests on
  every connected device, including a real TV on `adb connect`.
- On a real TV run single classes only (`-Pandroid.testInstrumentationRunnerArguments.class=ru.toxblh.luch.MediaCastTest`,
  with `-PluchDev` when a release is installed): `MirrorStartTest` presses `KEYCODE_SLEEP`, and on some projectors (XGIMI)
  that is standby with Wi-Fi off, so adb over the network is gone until someone turns it back on.
- Fix a bug test-first when you can: a failing `androidTest` that shows it, then the fix.

## Things that are easy to get wrong

- The emulator's decoders are software ones. Tests there show behaviour, not latency; latency needs a real TV.
- The mirror screen can be stopped and started again by the system (the TV waking up, its lock screen passing):
  `MirrorActivity` gives a stopped screen a second to come back before the session counts as closed, and
  `MirrorSession` makes the decoder again on a new surface.
- Android 14 NSD needs a service name unique on the device, hence `<name> · Luch`.
- Bringing up the picture from the background needs `appops set ru.toxblh.luch SYSTEM_ALERT_WINDOW allow`.
- Never put real hostnames, IP addresses or user names in screenshots, docs or tests: use `Living room TV`,
  `Linux Laptop`, `192.168.1.x` (the status screen has a demo mode: `--ez demo true`).

## Older Android

One APK from Android 5.0: calls newer than that go through `Compat` or a `Build.VERSION.SDK_INT` check, and
Android 8.0+ takes the same paths as before. What is worse on older versions is announced in the welcome
(`Caps.limits`). Before adding a call, check its API level (`./gradlew lintGithubRelease`); test on the 5.0, 6.0, 7.0
and 14 emulators: [docs/older-android.md](docs/older-android.md).
