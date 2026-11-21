# Android 5.0 to 7.x

Luch is one APK from Android 5.0 (`minSdk 21`), the first Android TV release. Android 8.0 and later take exactly the paths they always took; older versions take a fallback where an
API is missing, and the receiver says in its welcome what works worse (`sdk`, `limits`: PROTOCOL.md, section 5).
Nobody on the project has such hardware: everything below is checked on emulators (see the end).

## What each version lacks, and what Luch does instead

| Missing before | API | Fallback | Visible to the person |
|---|---|---|---|
| Android 11 (30) | `KEY_LOW_LATENCY`, `setPreferMinimalPostProcessing`, `Surface.setFrameRate` | not asked for | `decoder_low_latency`, `game_mode`: a few ms more, the TV may process the picture |
| Android 10 (29) | `WIFI_MODE_FULL_LOW_LATENCY` | `WIFI_MODE_FULL_HIGH_PERF` | `wifi_low_latency` |
| Android 8.1 (27) | `setTurnScreenOn`, `setShowWhenLocked` | window flags plus a `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` lock for 3 s at `start` (the flags alone do not wake a sleeping screen there) | none |
| Android 8.0 (26) | `startForegroundService`, notification channels, `AudioTrack` performance mode, `canRequestPackageInstalls`, `java.nio.file` | `startService`, `PRIORITY_MIN`, plain track, the system-wide "unknown sources" switch, `Compat.readFile` | none |
| Android 7.0 (24) | `AudioTrack.setBufferSizeInFrames` | none: the system's minimum buffer stays | **`audio_lag`**: the sound trails the picture by `audio_latency_ms` (46 ms on the emulator, often 100–150 ms on TVs). The sender chooses: no sound, lagging sound, or `"sync":true` (the receiver holds the picture back by that much) |
| Android 7.0 (24) | `Display.getHdrCapabilities`, `java.util.function`, `ConcurrentHashMap.newKeySet` | HDR left empty, own interface, `Collections.newSetFromMap` | `hdr_info` |
| Android 6.0 (23) | `Context.getSystemService(Class)`, `AudioTrack.Builder`, `write(byte[]…, WRITE_NON_BLOCKING)`, `Display.getSupportedModes`, `getActiveNetwork`, `ImageFormat.PRIVATE`, `KEY_PRIORITY` / `KEY_OPERATING_RATE` | `Compat.service` by name, the old `AudioTrack` constructor, `write(ByteBuffer…)`, the current mode only, `Compat.ipv4()`, the decoder bench decodes to memory, no decoder keys at all | `display_modes` |

Android 5.0 decoders accept unknown keys in `configure()` and then fail on the first buffer, too late for
`LowLatencyDecoder`'s retry loop, so below Android 6 the decoder gets the plain format. The emulator's software AVC
decoder (`OMX.google.h264.decoder`) takes only the Baseline profile; the bench then reports an error for AVC and
prefers HEVC, which is what a sender should use there. Real devices of that age usually decode High in hardware.

Android 5.0 also refuses activity starts from the background for 5 s after Home: the receiver asks again each second
for 8 s, then ends the session with `screen_not_shown`.

## Adding code

Check the API level of anything new (`./gradlew lintGithubRelease` reports NewApi as an error). Wrap it in
`Build.VERSION.SDK_INT >= N`, or add a method to `Compat` when the same call is needed in several places. If the
fallback is worse in a way a person would notice, add a limit to `Caps.limits()`, PROTOCOL.md section 5 and the
table in the README.

## Checking on emulators

Android TV images for Apple Silicon start at API 31, so the old versions are phone images
(`system-images;android-21|23|24;default;arm64-v8a`); the receiver does not depend on the TV UI.

```sh
SDK=~/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/sdkmanager "system-images;android-21;default;arm64-v8a"
echo no | $SDK/cmdline-tools/latest/bin/avdmanager create avd -n luch-21 -k "system-images;android-21;default;arm64-v8a" -d tv_1080p
$SDK/emulator/emulator -avd luch-21 -no-window -no-audio -no-snapshot -gpu guest &
cd android && ANDROID_SERIAL=emulator-5554 ./gradlew connectedGithubDebugAndroidTest
```

Run the whole suite on 21, 23, 24 and the API 34 TV image (`android-tv;arm64-v8a`) before a release.
`AudioModesTest` checks the welcome's `limits` and the `sync` hold for the version it runs on; `MirrorStartTest`
plays a sender with the codec the receiver's bench prefers.

Emulator decoders are software ones: these runs show that the code works on that Android version, not how fast a
real device decodes.

## Measuring latency

`LatencyMeasure` runs three 8-second sessions from a sender on the same device (picture only, picture and sound,
picture and sound with `sync`) and logs the receiver's own figures under the tag `LuchMeasure`:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ru.toxblh.luch.LatencyMeasure \
  -Pandroid.testInstrumentationRunnerArguments.measure=true
adb logcat -d -s LuchMeasure:I
```

On the emulators (2026-11-21, HEVC 1080p30, software decoder `OMX.google.hevc.decoder`, no network in between):

| Android | picture: decode | sound behind the picture | picture with `sync` |
|---|---|---|---|
| 5.0 | 7.7 ms | 46 ms (the buffer cannot be cut) | 7.6 + 46 = about 54 ms |
| 8.0 | 8.8 ms | 20 ms | 9.2 + 20 = about 29 ms |

What these are and are not: `decode` is from the frame entering the decoder to its picture coming out; the network
(a few ms on Wi-Fi), the sender's capture and encode, and the display's own delay come on top. The sound figure is
the receiver's audio buffer; the device's mixer and output add more, differently on every device, and the emulator
runs with `-no-audio`. On a real TV the hardware decoder decides the picture figure (about 6 ms on the MStar
projector Luch was built on); on Android 5 and 6 the system's audio buffer decides the sound one.
