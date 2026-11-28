# Notes for contributors and coding agents

Luch is a low-latency screen receiver for Android TV and projectors (Android 5.0+, one APK). The sender is a separate project,
[luch-linux](https://github.com/Toxblh/luch-linux). The wire protocol is in [PROTOCOL.md](PROTOCOL.md): change both
sides together.

## Layout

- `android/app/src/main/java/ru/toxblh/luch/`: the receiver, plain Java. The APK's only libraries are AndroidX
  Media3 1.8 (ExoPlayer with HLS, DASH and Smooth Streaming; the last Media3 that runs on Android 5.0) for cast
  media (`MediaActivity`), and in the github and play builds Jellyfin's `media3-ffmpeg-decoder` (prebuilt FFmpeg
  sound decoders: DTS, TrueHD, AC-3 on TVs without the licence). F-Droid's build has no prebuilt native code, so not
  the latter. Android's MediaPlayer played neither DASH nor HLS in fragmented MP4 or with separate sound tracks.
  Mirroring decodes with MediaCodec itself and uses none of it.
  - `LuchService`: the always-on service; `ControlServer`: the control ports, plain 47800 (older senders, asked
    about every time) and TLS 47802 (pairing, PROTOCOL.md 3a).
  - `Identity` (the TV's certificate, key in the keystore), `Pairing` (the code check), `Gate` (who is paired,
    refusal limits, guest mode; plain logic with a test clock), `PairPrompt` (the card in the corner),
    `MediaCrypt` (sealed frames). On-device: `PairingFlowTest` installs a `Gate` of its own; `TestSender` is a
    paired TLS sender by default.
  - `MirrorSession`: one session (UDP video and audio, decoding); `MirrorActivity`: its screen.
  - Input to the sender (PROTOCOL.md 4i): `InputLink` (the grant, the sending thread), `InputWire` (the state and
    its datagrams), `KeyMap` (Android key codes as evdev codes, gamepad bits), `Touchpad` (touchpad gestures); the
    last three are plain Java with JVM tests. `MirrorActivity` feeds them: a remote's keys stay on the receiver, only
    keyboards with letters, mice, devices with sticks and the touch screen go to the sender.
  - `Nearby`: Bluetooth LE (the card, PROTOCOL.md 1c, and another TV's wake for a sender, 4l); `BleWake`: the
    wake request's checks, plain Java with a JVM test.
  - "Direct" (PROTOCOL.md 4m): `BleLink` (the L2CAP server whose PSM is in the card, each channel bridged to the TLS
    port and known there as its Bluetooth device), `DirectGroup` (the Wi-Fi Direct group), `Direct` (the answers,
    names and passphrases, the group's lifetime, who is asked; plain Java with a JVM test).
  - `LowLatencyDecoder`: decoder setup; `Bench` and `Caps`: what the TV can do, sent in the welcome.
  - `LatencyOverlay`, `LatencyPattern`: the latency lab's pattern over the mirror screen (PROTOCOL.md, section 4j);
    the layout and the QR code must stay the same as luch-linux's `luch-latency/src/pattern.rs` (`LatencyPatternTest`).
  - `Reports`: crash and error reports kept on the TV and handed to a sender that asks (PROTOCOL.md, section 6).
    No server and no logs; keep addresses and names out of anything added to a report.
  - The log (PROTOCOL.md 6a): `Log` stands in for `android.util.Log` (the app's classes do not import Android's, so
    their lines also reach the file; don't add that import), `LogFile` (2 × 1 MB, a writer thread, a bounded queue
    that drops), `LogShare` (`{"t":"logs"}`, gzip in base64 parts). `ProblemReport` is the status screen's "Report a
    problem" (the log, `About`, kept reports; `Redact` hides addresses and names; `ReportProvider` shares the file).
    `ReceiverLogs`: the sender side of 6a, a phone showing its screen asks that receiver for its log over the same
    session (a new plain connection would show Allow on the TV, so other receivers are only listed). `ReportUpload`:
    "Send to the developers", the report as a GlitchTip (Sentry) event and its zip as an attachment, only in a build
    with `-PluchReportDsn=https://<key>@<host>/<project>` (`BuildConfig.REPORT_DSN`; empty, as in F-Droid's build:
    no button). The plain-Java ones have JVM tests. Never log keys, tokens or passphrases: the log is handed to senders.
  - The phone as a sender: `Link` (its control connection: TLS with its own key `luch-sender`, paired once with the
    code the TV shows (`PairCode` takes it) and pinned in `luch_pins`; the plain port only for a TV that has no TLS
    and was never paired, its requests sent again until Allow on the TV). `Sender` shows the screen over it and seals
    its media over TLS. "Show on TV" in the share sheet: `ShareActivity` (which TV: `ReceiverChoice`, the only one at
    once, the last used on top; `ReceiverBrowse` when the receiver is off), `SharePlan` (what was shared → photo,
    media, doc, open_app, or the screen with the file opened here for office files), `ShareServer` (the files over
    HTTP with Range, to that receiver only), `CastService` (the show, its notification: photos and pages turned,
    pause, stop). `Link`, `SharePlan`, `ShareServer`, `ReceiverChoice` and `Sender.frame` have JVM tests.
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
