# Luch — low-latency screen receiver for Android TV and projectors

[Русская версия](README.ru.md)

Luch ("ray" in Russian) turns an Android TV, a Google TV box or a projector into a screen your computer can cast
to, the way AirPlay and Chromecast work, but open and built for the lowest delay. This repository is the
**receiver** (Android). The sender for Linux is [luch-linux](https://github.com/Toxblh/luch-linux).

- **Push, like AirPlay and Cast.** The receiver is always on and announces itself on the local network
  (`_luch._tcp`); a sender finds it and starts a session.
- **Latency like Moonlight.** UDP shards with no jitter buffer, a frame goes to the hardware decoder the moment it
  is complete and is drawn the moment it is decoded; lost frames are recovered with an IDR request; the decoder is
  set up with every known low-latency key and the display is asked for its game mode. On a projector with a MediaTek
  (MStar) chip, capture to decode takes about 13 ms with HEVC.
- **Knows its hardware.** After install it measures each hardware decoder (H.264, HEVC, VP9, AV1) and tells senders
  which one decodes fastest, along with sizes, frame rates, 10-bit, HDR and audio formats.
- **Works next to a VPN.** Everything goes over the interface the TV was found on, never the default route.
- **Small.** About 6 MB of memory when idle; no Google services, no analytics, nothing beyond the local network.

## Install

Download the APK from [Releases](https://github.com/Toxblh/luch-android/releases) (or build it, below) and install
it with adb:

```sh
adb install luch-release.apk
# start it now (it starts by itself at every boot):
adb shell am start-foreground-service -n ru.toxblh.luch/.LuchService
# let it bring up the picture when a sender starts (Android TV has no settings page for this):
adb shell appops set ru.toxblh.luch SYSTEM_ALERT_WINDOW allow
```

Opening **Luch** from the TV's app list shows the TV's name and address, the connected senders and the senders
seen on the network.

The APK from Releases updates itself: it checks GitHub once a day and offers a newer release on that screen. After
the first update, which Android asks you to confirm (and to allow Luch to install apps), later ones install by
themselves while nothing is playing. Builds from F-Droid or an app store are updated by the store instead.
Details: [docs/updates.md](docs/updates.md).

## Android versions

Android 5.0 and later, one APK. Android 8.0 and later get everything; older versions work with less, and the receiver
tells the sender what (`limits` in its welcome, [PROTOCOL.md](PROTOCOL.md) section 5), so the sender can say so:

| Android | What is worse |
|---|---|
| 11+ | nothing |
| 8.0–10 | no low-latency decoder mode and no game mode on the display (Android 11); Wi-Fi low-latency mode needs 10 |
| 7.x | the same |
| 5.0–6.0 | **sound lags the picture** by the system's audio buffer (about 50–150 ms; Android 7 lets it be cut to 20 ms). The sender can leave the sound out, let it lag, or have the picture held back to match it (`sync`) |
| 5.x | no list of the display's modes; the decoder gets no low-latency keys at all |

## Use with luch-linux

Install [luch-linux](https://github.com/Toxblh/luch-linux) on the computer, then:

```sh
luchctl list                       # receivers on the network
luchctl mirror "<TV name>"          # mirror a monitor (also: window, virtual, fast)
luchctl stop "<TV name>"
```

On the TV, **OK** shows or hides the statistics (latency, bitrate, frames, decoder) and **Back** ends the session.

## Crash reports

When the receiver crashes or a session fails on an error (no decoder, no sound), it keeps a short report on the TV:
what failed, the stack trace, the Android version, the TV model and the session's settings. No logs, no addresses,
no names. It gives the reports only to a sender that asks for them, which shows them to you and lets you file them as
an issue; nothing is sent anywhere by the receiver itself. Details: [PROTOCOL.md](PROTOCOL.md), section 6.

## Build

JDK 17 and the Android SDK (platform 34):

```sh
cd android
./gradlew assembleFdroidRelease                    # unsigned, as F-Droid builds it (no self-update)
./gradlew assembleGithubRelease -PluchDebugSign    # signed with the debug key, for adb install
```

There are three builds: `github` (the APK on the Releases page, which updates itself), `fdroid` and `play` (the store
updates them; no self-update code or permission in them).

The decoder benchmark clips in `android/app/src/main/assets/bench` are made by `tools/gen_bench.sh` (ffmpeg's test
pattern); `tools/testsend.py <tv ip>` sends a test pattern to a receiver without luch-linux.

## Protocol

[PROTOCOL.md](PROTOCOL.md): discovery, the JSON control channel, the UDP media format, hardware capabilities and the
benchmark.

## License

[GPL-3.0-or-later](LICENSE). The approach to low-latency decoding follows
[moonlight-android](https://github.com/moonlight-stream/moonlight-android) (GPL-3.0).
