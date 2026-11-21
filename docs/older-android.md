# Supporting older Android versions

The receiver needs **Android 8.0** (`minSdk 26`). It can go down to **Android 5.0** (`minSdk 21`), the first Android
TV release: hardware MediaCodec decoders, Opus and SurfaceView are all there. Nobody on the project has hardware
older than Android 14, so this file says what to change and how to check it without such a TV.

The list comes from `lintGithubRelease` run with `minSdk 21` (NewApi and InlinedApi issues). Run it again after any change
to the code: it is the source of truth, this file is not.

```sh
cd android
sed -i.bak 's/minSdk 26/minSdk 21/' app/build.gradle    # the target you want
./gradlew lintGithubRelease                              # then read app/build/reports/lint-results-githubRelease.html
mv app/build.gradle.bak app/build.gradle
```

## What each step down needs

### Android 7.0 (`minSdk 24`): nothing gets worse

| Where | What | Change |
|---|---|---|
| `LuchService.start` | `startForegroundService` (26) | `startService` below 26 |
| `LuchService` notification | `NotificationChannel`, `Notification.Builder(context, channel)` (26) | no channel and the old `Builder(context)` below 26 |
| `MirrorSession` `AudioTrack.Builder` | `setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)` (26) | skip it below 26; the 20 ms `setBufferSizeInFrames` still works |

`Luch.name` reads `Settings.Global.DEVICE_NAME` (25), an inlined constant: below 25 it reads null and the existing
fallback to `Build.MODEL` takes over. Lint flags it; nothing to change.

### Android 6.0 (`minSdk 23`): audio latency goes up

Everything above, plus:

| Where | What | Change |
|---|---|---|
| `MirrorSession` audio | `setBufferSizeInFrames`, `getBufferCapacityInFrames` (24) | skip below 24. **Cost:** the track keeps its minimum buffer, 100–150 ms on the TVs we know, instead of about 20 ms. Video is not affected |
| `Bench.autoRun` | `java.util.function.Consumer` (24) | a small interface of our own (or core library desugaring, which adds a dependency) |
| `ControlServer` | `ConcurrentHashMap.newKeySet` (24) | `Collections.newSetFromMap(new ConcurrentHashMap<>())` |
| `Caps.display` | `Display.getHdrCapabilities` (24) | leave `hdr` empty below 24 |

### Android 5.0 (`minSdk 21`)

Everything above, plus:

| Where | What | Change |
|---|---|---|
| everywhere | `Context.getSystemService(Class)` (23) | `getSystemService(Context.POWER_SERVICE)` and so on, with a cast |
| `MirrorSession` audio | `AudioTrack.Builder` (23), `write(ByteBuffer…)` / `write(…, WRITE_NON_BLOCKING)` (23) | the old `AudioTrack(streamType, rate, channels, format, size, MODE_STREAM)` constructor and blocking writes |
| `Caps.display` | `Display.getSupportedModes` and `Display.Mode` (23) | leave `modes` empty below 23 (senders then fall back to 1920x1080) |
| `StatusActivity.address` | `ConnectivityManager.getActiveNetwork` (23) | `WifiManager.getConnectionInfo().getIpAddress()` below 23 |
| `LowLatencyDecoder`, `Bench` | `KEY_PRIORITY`, `KEY_OPERATING_RATE`, `ImageFormat.PRIVATE` (23) | these are inlined constants: harmless, the decoder ignores unknown keys, but keep `ImageFormat.PRIVATE` behind a check |

Low-latency decoding itself (`KEY_LOW_LATENCY`, `setPreferMinimalPostProcessing`, `Surface.setFrameRate`) is
Android 11 and is already behind version checks: on older systems the picture works, only without those requests.

## How to check without the hardware

There are no Android TV emulator images for Apple Silicon below API 31, but phone images exist for every level:
`system-images;android-21…29;default;arm64-v8a` (and `x86_64` on Intel). The receiver does not depend on the TV UI,
so a phone image is enough for everything except the leanback launcher entry.

```sh
SDK=~/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/sdkmanager "system-images;android-24;default;arm64-v8a"
echo no | $SDK/cmdline-tools/latest/bin/avdmanager create avd -n luch-24 -k "system-images;android-24;default;arm64-v8a"
$SDK/emulator/emulator -avd luch-24 -no-window -no-audio -gpu swiftshader_indirect &
cd android && ANDROID_SERIAL=emulator-5554 ./gradlew connectedGithubDebugAndroidTest
```

What to check on each level you claim:

1. `./gradlew lintGithubRelease` has no NewApi errors.
2. `connectedGithubDebugAndroidTest` passes (`MirrorStartTest`: a start with the screen asleep, a screen closed before the
   first picture, Back).
3. By hand, with `tools/testsend.py <emulator address>` or a real sender: the status screen opens, the service
   announces itself (`adb shell dumpsys activity services ru.toxblh.luch`), a stream shows a picture, and **audio
   plays** (the tests do not cover audio yet).
4. Note the `audio track: capacity … frames, using …` log line (tag `Luch`): on Android 6 and below the buffer stays
   at the system minimum.

Emulator decoders are software ones (`c2.android.*`, `OMX.google.*`) or goldfish passthroughs: they show that the code
works on that Android version, not how fast a real TV's decoder is.

Then update `minSdk` in `android/app/build.gradle`, the requirement in both READMEs and the F-Droid metadata.
