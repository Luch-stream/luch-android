# Luch protocol

Luch pushes a screen, a window, a video or photos from a computer (the *sender*) to a TV or projector (the
*receiver*), the way AirPlay and Cast do: the receiver is always on and announces itself, the sender lists what it
finds and starts a session. v0 is discovery and the control channel; v1 adds low-latency mirroring.

## 1. Discovery (mDNS / DNS-SD)

| Who | Service type | Port | TXT |
|---|---|---|---|
| Receiver | `_luch._tcp` | its control port (default 47800) | `v=0`, `id=<uuid>`, `name=<display name>`, `model=<model>`, `dec=avc,hevc` |
| Sender | `_luchsrc._tcp` | its control port (0 = not listening; still announced so receivers can list it) | `v=0`, `id=<uuid>`, `name=<host name>` |

- `id` is a random UUID made once and kept; names may change, `id` doesn't.
- Both sides browse the other's type, so each can show who is around.

## 2. Networks with a VPN (must work without any setting)

A full-tunnel VPN puts the default route, and often multicast, into the tunnel. Luch never relies on the default
route:

- **mDNS**: browse and publish on physical LAN interfaces only (Avahi does this by itself; tun/wg interfaces are
  not multicast). If you send multicast yourself, set `IP_MULTICAST_IF` per interface.
- **Connections**: every service found comes with the interface it was found on. All sockets to that peer, TCP and
  UDP, are bound to that interface (`SO_BINDTODEVICE`, or bind to that interface's IPv4 address before
  `connect`). Never let the kernel pick the source address by the default route.
- Prefer the peer's IPv4 address on that interface; use IPv6 link-local only with the scope id of that interface.

## 3. Control channel

TCP from sender to the receiver's port. UTF-8, one JSON object per line (`\n`), at most 64 KiB per line.
Every message has `t` (type). Unknown fields are ignored; an unknown `t` is answered with `{"t":"error","code":"unknown","for":"<t>"}`.

```
sender   → {"t":"hello","v":0,"id":"<uuid>","name":"<host>","app":"luchd/0.1","want":["mirror","media","photo"]}
receiver → {"t":"welcome","v":0,"id":"<uuid>","name":"<display>","model":"<model>",
            "decoders":["video/avc","video/hevc"],"max":"3840x2160","modes":[]}
either   → {"t":"ping","n":<int>}          answer {"t":"pong","n":<same>}
either   → {"t":"bye"}                     then close
```

- The receiver answers `hello` within 2 s or closes. `modes` lists what it can do now; v0 sends `[]`.
- Either side pings every 10 s while idle; 30 s without any line = dead, close.
- One sender at a time may hold a *session* later; v0 allows any number of connected senders.
- Pairing (v1): the first `hello` from an unknown `id` gets `{"t":"pair","digits":4}`, the TV shows a code, the
  sender answers `{"t":"pair","code":"1234"}`. Until then v0 shows nothing on screen except "<name> connected".

## 4. Mirroring (v1)

Modelled on Sunshine and Moonlight: UDP, no jitter buffer, loss answered with a fresh IDR.

```
sender   → {"t":"start","mode":"mirror","video":{"codec":"video/hevc","width":1920,"height":1080,"fps":60},
            "audio":{"codec":"audio/opus","rate":48000,"channels":2,"frame_ms":5,"sync":false}}
receiver → {"t":"ready","video_port":P,"audio_port":Q}      or {"t":"error","code":"start_failed","detail":"..."}
receiver → {"t":"keyframe"}                                 an IDR is needed (start, or after a loss)
either   → {"t":"stop"} / receiver → {"t":"stop","by":"receiver","reason":"back"|"screen_closed"|"screen_not_shown"}
receiver → {"t":"stats","decode_ms":..,"host_ms":..,"fps":..,"lost":..,"dropped":..,"shown_seq":N,"decoder":"..",
            "audio_latency_ms":N,"video_hold_ms":N}
sender   → {"t":"sender_stats","rtt_ms":..,"host_ms":..,"fps":..,"mbps":..,"encoder":"..","res":"WxH"}
```

The receiver ends a session itself with `reason` `back` (Back on the remote) or `screen_closed` (Home, another app
on top, or the screen staying off). A TV whose screen is asleep is woken by `start`; the picture may be stopped for a
moment while the TV wakes up, so a stopped screen gets a second to come back before the session counts as closed,
and the receiver asks for a `keyframe` for the new surface. If the system will not open the screen at all (for 5 s
after Home on older Android, or without the "display over other apps" permission), the receiver asks again each
second and after 8 s ends the session with `screen_not_shown`.

**Audio modes.** No `audio`: picture only, the lowest latency. `audio` without `sync`: the sound plays as soon as
it is decoded, `audio_latency_ms` (stats) after the picture. `"sync":true`: the receiver holds each picture back by
that much (`video_hold_ms` in stats), so both come out together, at the cost of that much picture latency. On
Android 7+ the receiver cuts the audio buffer to about 20 ms and `sync` is not worth it; on Android 5 and 6 it
cannot (`limits` in the welcome, section 5), and the sender should let its user choose between the three.

The sender sends from a socket bound to the LAN interface (section 2); the receiver takes the sender's address from
the first packet. `codec` should be `bench.prefer` (section 5) when the sender can encode it.

**Packet**: a 24-byte big-endian header, then a data shard of up to 1376 bytes.

| Offset | Size | Field |
|---|---|---|
| 0 | u8 | kind: 1 video, 2 audio |
| 1 | u8 | flags: bit0 IDR, bit1 codec config, bit2 parity shard |
| 2 | u16 | shard index |
| 4 | u16 | data shards in the frame |
| 6 | u16 | parity shards (0 for now; Reed-Solomon FEC later) |
| 8 | u32 | frame sequence, per kind, +1 per frame |
| 12 | u32 | pts in µs (capture time, low 32 bits; one clock for video and audio) |
| 16 | u32 | frame length; the frame is the data shards joined and cut to this |
| 20 | u16 | host latency, capture to send, in 0.1 ms |
| 22 | u16 | reserved, 0 |

**Video**: one access unit per frame, Annex-B (H.264/HEVC), no B-frames, one reference frame, an IDR only when asked
for, parameter sets inside every IDR access unit.
**Audio**: Opus, one packet per datagram. A codec-config packet (flags bit1) carries the 19-byte OpusHead; it comes
first and again with every video IDR.

**Receiver rules**: a frame goes to the decoder the moment its last shard arrives. A frame is lost when a packet of a
later frame arrives; after a loss everything is dropped until an IDR, which is asked for once a frame completes
again, and again after about RTT + 100 ms if none comes. Of the frames decoded at once only the newest is drawn.

## 5. What the receiver can do (`hw`, `bench` in welcome)

`welcome` also carries:

- `hw`: read from the hardware, instant. `video`: per MIME type with a hardware decoder, `decoder`, `max` (WxH),
  `1080p60`, `1080p120`, `2160p30`, `2160p60`, `10bit`, `low_latency` (booleans). `audio`: MIME types with any
  decoder. `display`: `modes` ("WxH@Hz"), `hdr`, `game_mode` (minimal post-processing / ALLM).
- `bench`: measured, or `null` until done. Per video MIME type `{ms, p95, frames, decoder}` (or `{error}`): decode
  latency at 1920x1080, 60 fps, low-latency configuration, queueInputBuffer → output. `prefer`: the MIME type to
  encode for (fastest; an earlier entry in avc, hevc, vp9, av1 wins unless a later one is 0.5 ms faster).

The receiver measures by itself after install, an app update or a firmware update, and at a sender's first hello
when there is no result yet, but only while nothing plays (a video would lose its decoder); then it sends
`{"t":"bench_result", ...same fields...}` to every connected sender. `{"t":"bench"}` measures now (answered with
`bench_result`, or `{"t":"error","code":"busy","for":"bench"}` during a session).

- `sdk`: the Android API level (21 = Android 5.0 ... 34 = Android 14).
- `limits`: `{"audio_latency_ms":N, "limits":[...]}`, what works worse here than on Android 8.0+, for the sender to
  show and to pick modes by. `audio_latency_ms` is how long the sound will trail the picture (about 20 on Android 7+).
  Limits: `audio_lag` (Android 5 and 6: the audio buffer cannot be cut; offer no sound, lagging sound or `sync`),
  `decoder_low_latency` and `game_mode` (before Android 11: no low-latency decoder flag, no minimal post-processing
  request to the display), `wifi_low_latency` (before Android 10), `hdr_info` (before 7: HDR support unknown),
  `display_modes` (Android 5: only the current display mode is listed). Unknown limits are to be shown as is.

Senders: encode `bench.prefer` when they can, else `video/avc`. On Android 5 the AVC decoder may only take the
Baseline profile (emulators do; real TVs usually decode High in hardware): the bench then reports an
error for `video/avc` and prefers another codec.

## 6. Crash and error reports

The receiver keeps reports of its crashes and of failures the person will have noticed (no decoder, a decoder that
keeps failing, the picture or the sound stopping on an error): at most 10, a repeat of the same failure only counting
up. It hands them only to a sender that asks with `"reports"` in the `want` of its hello, right after the welcome; an
older sender never sees them. The receiver forgets a report once the sender says it has it.

```
receiver → {"t":"reports","items":[{
              "id":"<uuid>", "kind":"crash"|"error", "time":<ms since 1970>, "last_time":<ms, on repeats>, "count":N,
              "what":"<exception or failure>", "thread":"luch-video", "stack":"<Java stack trace, at most 8 KB>",
              "details":{"codec":"video/hevc","width":1920,"height":1080,"fps":60,"decoder":"..","audio":true,
                         "frames_in":N,"rendered":N,"lost":N},
              "app":"0.2", "android":"14", "sdk":34, "device":"<manufacturer> <model>", "hardware":"<board>",
              "signature":"<what tells two failures apart>"}]}
sender   → {"t":"reports_taken","ids":["<uuid>",...]}
```

A report holds no logs, no addresses (IPv4 and IPv6 in messages and stack traces become `<ip>`), no device name
given by the person and no sender names. The sender decides what to do with it: luchd shows it to its user and offers
to file it as an issue. Sending reports to a server must stay opt-in on the sender's side.

## 7. Later versions (reserved)

Reed-Solomon FEC shards (the header already has room); pairing with a code shown on the TV; `{"t":"media","url":...}`
with `play/pause/seek/rate/track/sub` and `state` reports, the TV playing the original file; `{"t":"photo",...}`;
`{"t":"key","code":...}` from the TV remote back to the sender (next slide, pause).
