# Notes for contributors and coding agents

Luch is a low-latency screen receiver for Android TV. The sender is a separate project,
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
- `cd android && ./gradlew assembleRelease`: unsigned, as F-Droid builds it; `-PluchDebugSign` signs with the debug key.
- `ANDROID_SERIAL=<emulator> ./gradlew connectedDebugAndroidTest` on an Android TV emulator (API 34
  `android-tv;arm64-v8a` works on Apple Silicon). **Always set `ANDROID_SERIAL`**: Gradle otherwise runs the tests on
  every connected device, including a real TV on `adb connect`.
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

The minimum is Android 8.0. What it takes to go down to 5.0, and how to check it without such a TV:
[docs/older-android.md](docs/older-android.md).
