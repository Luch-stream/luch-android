# Luch — low-latency screen receiver for Android TV

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

## Use with luch-linux

Install [luch-linux](https://github.com/Toxblh/luch-linux) on the computer, then:

```sh
luchctl list                       # receivers on the network
luchctl mirror "<TV name>"          # mirror a monitor (also: window, virtual, fast)
luchctl stop "<TV name>"
```

On the TV, **OK** shows or hides the statistics (latency, bitrate, frames, decoder) and **Back** ends the session.

## Build

JDK 17 and the Android SDK (platform 34):

```sh
cd android
./gradlew assembleRelease                    # unsigned, as F-Droid builds it
./gradlew assembleRelease -PluchDebugSign    # signed with the debug key, for adb install
```

The decoder benchmark clips in `android/app/src/main/assets/bench` are made by `tools/gen_bench.sh` (ffmpeg's test
pattern); `tools/testsend.py <tv ip>` sends a test pattern to a receiver without luch-linux.

## Protocol

[PROTOCOL.md](PROTOCOL.md): discovery, the JSON control channel, the UDP media format, hardware capabilities and the
benchmark.

## License

[GPL-3.0-or-later](LICENSE). The approach to low-latency decoding follows
[moonlight-android](https://github.com/moonlight-stream/moonlight-android) (GPL-3.0).
