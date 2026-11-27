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
           may also carry "battery" (percent), "charging", "thermal"
           ("none|light|moderate|severe|critical|emergency|shutdown"), "power_save", "interactive"
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

## 4a. Photos

A sender shows pictures full screen, one at a time, in a list the person can go through on the remote. The receiver lists
`"photo"` in `modes` (welcome) when it can; the sender says `"photo"` in `want`.

```
sender   → {"t":"photo","index":0,"items":[{"url":"http://<sender>:<port>/<token>/0","mime":"image/jpeg","name":"IMG_1.jpg"}, ...]}
receiver → {"t":"photo_ready"}            or {"t":"error","code":"photo_failed"|"busy","detail":"..."}
either   → {"t":"stop"}                   receiver adds "by":"receiver","reason":"back" when the person leaves
```

- The files do not go over the control channel (a line is at most 64 KiB). Each `url` is a plain HTTP GET the sender serves
  for the time of the session, with a random token in the path and only to the receiver's address. At most 200 items.
- The receiver must refuse a list whose links point anywhere but the address the control connection came from, and fetch
  with a plain `HTTP/1.0 GET` (Android refuses cleartext through its own HTTP stack; the link is on the local network).
- A new `photo` replaces the list. The receiver shows each picture fitted on black, rotated by its EXIF orientation,
  down-scaled to what the screen can use; left and right on the remote (or a swipe) go to the neighbour, Back leaves.
- Not while a mirroring session is running: `{"t":"error","code":"busy","for":"photo"}`.

## 4b. Media

A sender hands the receiver a video (or a sound) to play itself, in the file's own quality, the way Cast and AirPlay
do: the picture does not depend on the sender's screen. The receiver lists `"media"` in `modes` when it can.

```
sender   → {"t":"media","via":"direct"|"sender",
            "src":[{"url":"...","mime":"video/mp4"|"application/vnd.apple.mpegurl"|...,"w":3840,"h":2160,"codec":"hevc"}],
            "headers":{"Referer":"...","User-Agent":"...","Cookie":"..."},
            "title":"...","poster":"<url>","start":12.5,"rate":1.0,"paused":false,
            "subs":[{"id":"s1","url":"...","mime":"text/vtt"|"application/x-subrip","lang":"en","label":"English"}]}
receiver → {"t":"media_ready"}            or {"t":"error","code":"media_failed"|"busy"|"bad_link","for":"media","detail":"..."}
receiver → {"t":"media_state","state":"loading"|"playing"|"paused"|"ended"|"error","pos":12.5,"dur":3600.0,
            "rate":1.0,"live":false,"w":3840,"h":2160,"by":"receiver"|"sender",
            "tracks":{"audio":[{"id":"a0","lang":"en","label":"..."}],"sub":[{"id":"s1","lang":"en","label":"English"}]},
            "sel":{"audio":"a0","sub":null},"detail":"<on error>"}
sender   → {"t":"media_cmd","do":"play"|"pause"|"toggle"|"seek"|"skip"|"rate"|"audio"|"sub"|"state",
            "pos":<s, seek>,"by":<s, skip, may be negative>,"rate":<rate>,"id":<track id, audio/sub; null = subtitles off>}
either   → {"t":"stop"}                   receiver adds "by":"receiver","reason":"back"|"ended" when it ends by itself
```

- **What is played.** `src` lists the same content in one or more forms (the `<source>`s of a page, or the variants a
  site offers); the receiver plays the best one it can: a MIME type it can play, a codec it can decode (`decoders`),
  no bigger than its `max`, then the biggest. An HLS or DASH playlist is one `src`: the receiver picks its variant
  itself, up to its own screen, which is what makes a 4K video play in 4K from a sender with a 1080p screen.
- **Who fetches.** `via:"direct"`: the receiver fetches from the internet itself (the sender may be switched off
  meanwhile). It must refuse links to loopback, link-local and private addresses other than the sender's own (a
  sender must not make the TV reach into its network), with `bad_link`. `headers` (only `Referer`, `User-Agent`,
  `Origin`, `Cookie`, `Authorization`; others are ignored) go with every request. `via:"sender"`: every link points at
  the sender's address, as for photos (section 4a): local files, or a stream the sender relays (a page behind a VPN, a
  site that needs the browser's cookies, Wi-Fi Direct). The sender serves HTTP/1.1 with `Range`, so the receiver can seek.
- **State.** The receiver owns the playback; a sender (and the page, the remote, a phone) only asks. `media_state`
  goes to the sender that started it on every change of `state`, `rate`, tracks or a seek, and every 5 s while
  playing; between them the sender moves `pos` on by `rate` × elapsed time. `"do":"state"` asks for one now. `by` says who caused the change (the
  remote or a `media_cmd`), so a sender can show "paused on the TV". `dur` is `null` for a live stream (`live:true`).
- **On the TV.** OK or Play/Pause toggles, left/right skip 10 s (held: the target moves on, 30 s steps after a while,
  and the seek happens when the key is let go), up shows the controls, down, the menu key or a long OK opens speed,
  sound track and subtitles, Back stops. The controls hide after 3 s.
- A new `media` replaces the one playing. Not while a mirroring session or photos are shown: `busy`. Subtitles from
  `subs` are fetched under the same rules as the media; WebVTT, SubRip and ASS/SSA (text only) are understood.

## 4c. Sleep

```
sender   → {"t":"sleep"}
receiver → {"t":"sleeping"}               then the TV goes to standby, as with the remote's power key
           or {"t":"error","code":"sleep_unavailable","for":"sleep","sleep":"off"|"no"}
```

`welcome` carries `"sleep"`: `"on"` (works), `"off"` (the person has not turned it on: Android lets an app put the
screen to sleep only through an accessibility service, which the receiver asks for once) or `"no"` (before Android 9).

## 4d. Sound only, now playing and media keys

A sender plays its sound on the receiver like a speaker: `start` with `"mode":"audio"` and an `audio` block, no
`video` (section 4). The receiver lists `"audio"` in `modes` when it can, answers `ready` as usual (`video_port` may
be ignored) and shows what plays instead of a picture, or nothing at all on a device without a screen.

```
sender   → {"t":"now_playing","title":"...","artist":"...","album":"...","app":"Spotify","art":"<sender link>",
            "state":"playing"|"paused"|"stopped","pos":12.5,"dur":215.0,"can":["play_pause","next","previous","seek"]}
receiver → {"t":"media_key","key":"play_pause"|"play"|"pause"|"next"|"previous"|"seek_forward"|"seek_back"|"stop"}
```

- `now_playing` may come at any time while a session runs (sound only or mirroring), whenever the sender's player
  changes; every field may be left out. `art` follows the photo rules (section 4a: a link on the sender).
- `media_key`: what the remote asks for when the sender's player is the one to obey: the media keys during any
  session, and in sound only also OK (play_pause) and left/right (previous/next). A sender passes it to its own
  player (MPRIS on Linux) and sends a fresh `now_playing`. The receiver never sends it while it plays `media` itself:
  then its own player obeys.

## 4e. Volume

```
sender   → {"t":"volume","level":0..100}  or {"t":"volume","step":1|-1}  or {"t":"volume","muted":true|false}
receiver → {"t":"volume","level":N,"muted":B}         its music volume now: after a change from either side
```

`welcome` carries the same `"volume":{"level":N,"muted":B}` when the receiver lets apps set its volume, and leaves it
out when it does not (some TVs keep the volume in the amplifier, out of Android's reach).

## 4f. The TV's own apps

Some links play better in the TV's own app than as a stream: the app has the person's account, and the site hides
its stream from a browser. The receiver lists the apps it knows and has in `welcome` as `"apps":["youtube"]`.

```
sender   → {"t":"open_app","app":"youtube","id":"<video id>","start":<s>}
receiver → {"t":"app_opened","app":"youtube"}   or {"t":"error","code":"no_app","for":"open_app"}
```

`youtube`: `id` is the video id (`v=` of a watch link, the path of a youtu.be link). The app then plays on its own,
outside Luch: there is no `media_state` for it.

## 4g. Documents

```
sender   → {"t":"doc","url":"<sender link>","mime":"application/pdf","name":"...","page":0}
receiver → {"t":"doc_ready","pages":N}    or {"t":"error","code":"doc_failed"|"busy","for":"doc","detail":"..."}
either   → {"t":"doc_page","page":n}      the receiver after every turn of a page, the sender to turn one
either   → {"t":"stop"}
```

PDF only: a sender turns other documents (text, office files, code) into PDF first. The link follows the photo
rules (section 4a). The receiver draws each page sharp at its own resolution. On the TV, left/right turn the page,
OK switches between the whole page and the page's width (then up/down scroll), Back stops.

## 4h. Input: the receiver drives the sender

During a mirroring session the receiver's touch screen, stylus, keyboard, mouse and gamepads can control the
sender: a tablet showing a laptop's desktop, a TV with a keyboard or a gamepad plugged in. The sender's person
decides first.

```
receiver → welcome … "input":["touch","stylus","keyboard","mouse","gamepad"],"touch_points":10
sender   → hello  … "want":[…,"input"]
receiver → {"t":"input_request","devices":["touch"]}
sender   → {"t":"input","grant":"once"|"always","token":"<16 hex digits>","accept":["touch","pointer","keyboard","gamepad","pen"],
            "area":[2560,1440]}
           or {"t":"input","grant":"no"}
sender   → {"t":"input","grant":"off"}            control taken back during the session
```

- **Capabilities.** `input` in the welcome lists what the receiver can capture at all (`touch` for a touch screen,
  `stylus` when a stylus has been seen or the screen takes one, `keyboard`, `mouse`, `gamepad` for what is plugged in
  now or can be). A sender that can inject input says `"input"` in its hello's `want`; without it the receiver never
  asks and keeps its touch screen for its own controls.
- **Asking.** The receiver sends `input_request` the first time the person uses a device of a kind in a session
  (the first touch on the picture, the first key of a keyboard), and again for a kind not asked for before. Input is
  not sent before a grant; what is done meanwhile is dropped. The sender asks its person ("May Living room TV
  control this computer?": allow once / always / no). `always` is remembered per receiver `id`: the next session's
  grant comes right after `ready`, without a request. `no` holds for the session: the receiver asks no more.
- **The grant.** `token` goes into every input datagram; `accept` is what the sender can inject now (a sender
  that has no touch screen device turns touches into the pointer itself; `pen` with pressure needs uinput on Linux,
  see luch-linux `docs/input.md`). `area` is the size of the shown area in the sender's logical pixels, for relative
  motion. A grant ends with the session.
- **The remote stays the receiver's.** Keys of the TV remote (D-pad, OK, Back, Home, media keys) keep their meaning
  on the receiver; only full keyboards, mice, gamepads, the touch screen and the stylus go to the sender. Back from
  the system (a phone's gesture or button) still ends the session.

**Datagrams.** Input goes as UDP from the receiver's video socket to the address and port the video comes from: the
sender's firewall lets it in as the answer of a flow it opened, no port is added to `ready`, and the VPN rules of
section 2 hold by themselves. The receiver marks it DSCP EF (the Wi-Fi voice queue). The first byte is the kind,
as in section 4, but the header is the input one:

| Offset | Size | Field |
|---|---|---|
| 0 | u8 | kind: 3 input |
| 1 | u8 | version: 0 |
| 2 | u16 | reserved, 0 |
| 4 | u32 | packet sequence, +1 per packet; older or repeated ones are dropped (compared with wrap-around) |
| 8 | u32 | time of the newest change in it, µs on the receiver's clock (low 32 bits) |
| 12 | u64 | token from the grant (16 hex digits = 8 bytes, big-endian) |
| 20 | … | records: u8 type, u8 length of what follows, then that many bytes; unknown types are skipped |

All numbers are big-endian. Positions are normalized to the picture: `x`, `y` are 0…65535 from the left/top edge
to the right/bottom edge of the video frame as sent (the receiver's own bars and crop are taken out). The records
carry **state, not events**, so a lost datagram loses nothing but one in-between position:

| Type | Record | Payload |
|---|---|---|
| 1 | touch | u8 n, then n × {u8 id, u8 tool, u16 x, u16 y, u16 pressure}: every contact that is down now. `id` stays the same while a contact is down (0…15). `tool`: 1 finger, 2 stylus, 3 eraser; +0x40 the stylus's button is held, +0x80 hovering (near the screen, not touching). `pressure` 0…65535, 0 when unknown. n = 0: everything is up. |
| 2 | pointer | u8 buttons (bit 0 left, 1 right, 2 middle, 3 back, 4 forward), u8 flags (bit 0 `x`/`y` valid, bit 1 the wheel is smooth: a touchpad), u16 x, u16 y, i32 rel x, i32 rel y (running totals of relative motion since the session began, 1/16 of a logical pixel of `area`), i32 wheel y, i32 wheel x (running totals, 120 per wheel notch, positive = down / right) |
| 3 | keys | u16 number of the first event, u8 n, n × {u16 code, u8 1 down / 0 up}, then u8 h, h × u16 code held now. Codes are Linux evdev `KEY_*`/`BTN_*` numbers. |
| 4 | gamepad | u8 index (0…3), u8 flags (bit 0 connected; 0 = unplugged), u32 buttons, i16 left x, left y, right x, right y (−32768…32767, y positive down), u16 left trigger, right trigger (0…65535) |

- **Running totals and event numbers** make loss harmless: the sender moves the pointer by the difference to the
  last totals it saw, and applies key events whose number is newer than the last it applied (u16, wrap-around).
  The receiver repeats each key event in every datagram for 300 ms (at most 32 events); `held` heals the rest: the
  sender releases a key it holds that is not listed, but presses none from it. Key repeat is the sender's own.
- **Gamepad buttons**: bit 0 A (south), 1 B (east), 2 X (west), 3 Y (north), 4 back/select, 5 guide, 6 start,
  7 left stick, 8 right stick, 9 left shoulder, 10 right shoulder, 11 D-pad up, 12 down, 13 left, 14 right (the SDL
  order).
- **When.** A datagram goes out on every change, at once (the newest state replaces one not yet sent: no queue).
  While anything is held or moving (a contact, a button, a key, a gamepad off centre) the state is sent again every
  50 ms, and three more times after everything is let go. Each datagram carries every record of a device used in
  the session (several gamepad records when several are plugged in).
- **The sender's watchdog.** No datagram for 500 ms while something is held: everything is let go (the Wi-Fi went,
  the receiver crashed). A datagram from another address or port, with a wrong token or before the grant, is dropped.
- The token keeps out a blind injection on the LAN; it travels in the clear like the rest of the control channel.

**On the receiver** (Android): *direct* mode puts a touch where it is seen (real multitouch, the stylus with its
pressure); *touchpad* mode moves the pointer by finger motion (`rel`), a tap clicks, a two-finger tap right-clicks, two
fingers scroll (`wheel`, smooth), a tap and then a drag holds the left button. A mouse is captured (Android 8+) and
sends relative motion, so only the sender's cursor (in the picture) is seen; before Android 8 its position goes as
`x`/`y`. A three-finger tap opens the receiver's own panel, where the mode is chosen.

Not covered (yet): a phone as the controller of a TV session (the reverse direction), input from the web receiver,
and an Android sender obeying input (it would need an accessibility service).

## 5. What the receiver can do (`hw`, `bench` in welcome)

`welcome` also carries:

- `hw`: read from the hardware, instant. `video`: per MIME type with a hardware decoder, `decoder`, `max` (WxH),
  `1080p60`, `1080p120`, `2160p30`, `2160p60`, `10bit`, `low_latency` (booleans). `audio`: MIME types with any
  decoder. `display`: `modes` ("WxH@Hz"), `hdr`, `game_mode` (minimal post-processing / ALLM). `device`: a snapshot
  of `battery`, `charge_mah`, `charging`, `thermal`, `power_save`, `interactive` when available.
  `display` also carries `current` (rotated WxH@Hz), `rotation` (degrees), `refresh` (Hz), and `type` (tv/tablet/phone).

- `bench`: measured, or `null` until done. Per video MIME type `{ms, p95, frames, decoder}` (or `{error}`): decode
  latency at 1920x1080, 60 fps, low-latency configuration, queueInputBuffer → output. `prefer`: the MIME type to
  encode for: the fastest measured decoder, with codecs within 0.5 ms of the fastest treated as tied.
  Ties favour compression efficiency: AV1, HEVC, VP9, then H.264. This is decode latency only; the sender
  must account for its own encode latency and available encoders before choosing the stream codec.

The device fields are event-driven: the receiver caches them from system callbacks (a sticky battery
broadcast, screen on/off, power-save and thermal listeners) and repeats them in `stats` **only when they
change**. A sender must keep the last value it saw and must not assume the fields are present.

`battery` (percent) and `charge_mah` (milliamp-hours, when the device exposes a charge counter) are sent
with a hysteresis: only when the counter moved by 100 mAh (or 3 percentage points if there is no counter).
`charging` is sent immediately when the cable goes in or out, and `thermal`, `power_save` and `interactive`
on every change, all in the same `stats` line that already flows once a second.
`thermal_headroom` in `stats` (and `headroom` in `hw.device`) is optional and cached for ten seconds:
0 means low thermal load, 1 means the severe throttling threshold, and values can exceed 1.
The heat badge appears at 0.7 or higher, or at a thermal status of moderate or worse.

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

Reed-Solomon FEC shards (the header already has room); pairing with a code shown on the TV; `media` (done, section
4b); `{"t":"photo",...}` (done, section 4a: a sender-served link, no inline data);
`{"t":"key","code":...}` from the TV remote back to the sender (next slide, pause); input from the receiver's
keyboards, mice, gamepads and touch screen is section 4h.
