# Luch protocol

Luch pushes a screen, a window, a video or photos from a computer (the *sender*) to a TV or projector (the
*receiver*), the way AirPlay and Cast do: the receiver is always on and announces itself, the sender lists what it
finds and starts a session. v0 is discovery and the control channel in plain TCP; v1 is the same channel over TLS,
with pairing (section 3a) and sealed media (section 4). How it is kept safe, and why so, is in section 8.

## 1. Discovery

A sender finds receivers by itself, whatever the network: a ladder it climbs while nothing is found, each rung
saying what it tries. Everything found any way is checked with the real `hello` (section 3) before it is listed,
and joins the same list. A sender never sends anything through a VPN interface to find a receiver (section 2).

| Rung | Starts | Needs | Typical time to a receiver |
|---|---|---|---|
| 1a mDNS | at once | multicast on the LAN | < 1 s |
| 1b sweep of the subnet | at once, with 1a | unicast between devices | 0.1–2 s on a /24 |
| 1c Bluetooth LE | after 4 s with nothing (or when 1a and 1b both ended) | both in Bluetooth range, then unicast | 1–3 s |
| 1d sound | only when the person asks, after 1c | the TV's speaker and the sender's microphone in one room | 2 s per frame |
| 1e QR code | only when the person asks, with 1d | the sender's camera facing the TV | < 1 s once in view |

Sound and the camera need the person (the TV speaks when asked, the code is held up), and open the microphone and
the camera: a sender climbs to them only when its person asked it to look harder. luchd: `Find("all")`,
`luchctl find`, "Search harder" in GNOME; `Refresh` climbs 1a–1c by itself (docs/dbus.md).

### 1a. mDNS / DNS-SD

| Who | Service type | Port | TXT |
|---|---|---|---|
| Receiver | `_luch._tcp` | its plain control port (default 47800) | `v=0`, `id=<uuid>`, `name=<display name>`, `model=<model>`, `dec=avc,hevc`, `tls=<TLS port>` (default 47802) |
| Sender | `_luchsrc._tcp` | its control port (0 = not listening; still announced so receivers can list it) | `v=0`, `id=<uuid>`, `name=<host name>` |

- `id` is a random UUID made once and kept; names may change, `id` doesn't.
- Both sides browse the other's type, so each can show who is around.
- `v` stays `0`: it is the plain port's version, which every sender speaks (older senders skip any other `v`). `tls`
  says that the receiver also speaks v1 there. Nothing in a TXT record is trusted: a sender that paired with a
  receiver id trusts only that receiver's certificate under it (section 3a), whatever mDNS says.

### 1b. Sweep of the subnet

For networks that drop multicast (hotel and guest Wi-Fi, some mesh systems), a receiver is still a TCP port. The
sender connects to port 47800 of every address of each LAN interface's subnet and says a probe hello where a
connection is accepted:

```
sender   → {"t":"hello","v":0,"id":"<uuid>","name":"<host>","app":"luchd/0.4","want":[],"probe":true}
receiver → {"t":"welcome",...,"probe":true}     the usual welcome
sender   → {"t":"bye"}
```

- A receiver answers a probe like any hello but keeps no session, shows nothing and runs no bench.
- Interfaces: up, running, not loopback and not point-to-point (tun, wg, ppp), nor by name a tunnel or a local
  bridge (`tap`, `tailscale`, `zt`, `docker`, `veth`, `virbr`...). Sockets are bound to the interface (section 2).
- Addresses: the whole subnet, but at most a /22 (1024 addresses) around the sender's own (a hotel's /16 is cut to
  where a DHCP server handing out addresses in order most likely put the TV too); neighbours the kernel already
  knows (its ARP table) first, then nearest to the sender's own address first.
- Bounds: 128 connections in flight, 400 ms to connect, 2 s for the welcome, 20 s in all. A /24 with no host on most
  addresses takes about 1 s.
- A receiver found this way (or by 1c–1e) leaves the list when it stops answering at a later climb; mDNS has its
  own removal.

### 1c. Bluetooth LE

The receiver advertises its **card** (below) as manufacturer data, company id `0xFFFF` (the id the Bluetooth SIG
keeps for tests and for projects without one; the card's `Lu` magic tells it apart). Non-connectable, no name, no
pairing token: flags (3 bytes) + manufacturer data (4 + 13) = 20 of the 31 bytes of a legacy advertisement, so it
works on every controller from Bluetooth 4.0. Once a second normally, ten times a second for 2 minutes after it
heard a sender looking.

The sender scans (BlueZ: `SetDiscoveryFilter` with `Transport=le`, `DuplicateData=true`; `StartDiscovery` for
12 s) and connects to each card's address and port as in 1b. Receivers heard are listed nearest first (strongest
RSSI). While it scans it advertises a "looking" card (kind 2: magic, kind, its id), non-connectable; a receiver
that hears one may tell its person ("A computer nearby is looking for a TV").

Android: advertising needs `BLUETOOTH_ADVERTISE` (a runtime permission, "Nearby devices", Android 12+) or
`BLUETOOTH`/`BLUETOOTH_ADMIN` (install time, before 12), Bluetooth on, and a controller with LE advertising
(`getBluetoothLeAdvertiser() != null`; many TVs before Android 8 have none). Listening for senders needs
`BLUETOOTH_SCAN` with `neverForLocation`, so only on Android 12+: before it, a scan needs the location permission,
which a TV app should not ask for. Without any of these the receiver goes on without Bluetooth.

### 1d. Sound

On its person's request ("Say the address by sound" on the TV) the receiver plays its card with the pairing token,
three times, 0.4 s apart. A sender listens on its microphone.

- 16-tone FSK, like ggwave: one tone of 16 per 40 ms symbol (4 bits), tone k at `base + 100·k` Hz, `base` = 17000 Hz
  (near-ultrasonic: 17.0–18.5 kHz, hardly heard by adults, carried by TV speakers and laptop microphones) or
  1800 Hz (an audible fallback for speakers that cut the top; senders listen for both). Every tone fades in and out
  over 2 ms, so symbol changes do not click. Peak at half full scale, at the TV's volume.
- A frame: the preamble (tones `0 15 3 12 6 9 1 14`, all different so that a window a symbol off matches nothing),
  then a Reed-Solomon codeword of `[len, payload...]` over GF(256) (polynomial 0x11d, first root α⁰, 8 parity bytes:
  4 wrong bytes are mended), each byte as two symbols, high nibble first. Payload at most 32 bytes.
- Receiving: Goertzel energies of the 16 tones over the middle three quarters of each symbol (Hann window); the
  preamble is searched on an eighth of a symbol and refined to 16 samples; each symbol's timing may move by 1/64 of a
  symbol to follow a sample clock that runs fast or slow; the length is the one whose codeword corrects and agrees.
- Proven in memory only (both sides' tests): 40 dB down with noise as loud as the signal, sample clocks 500 ppm apart
  (a cheap crystal is within 100), a 120 ms clap over three symbols, a steady whistle on a tone, 44.1 kHz output
  resampled to 48 kHz. Test vector: the card `4c75013f0c2a8ec0a80114bab80badf00d` is the tones
  `0f3c691e114c75013f0c2a8ec0a80114bab80badf00d1f8320c0e4ed937c`; `rs("Luch")` = `4c7563681c7c9b839d89732d`.

### 1e. QR code and the pairing token

The receiver's screen shows a QR code (byte mode, error correction H):
`luch://connect?host=<IPv4>&port=<port>&t=<token>`. A phone with Luch opens it; a computer reads it through its
camera, or a person passes it as text (luchd `AddReceiver`). Unknown parameters are ignored; `id=<8 hex>` may be
given and is then checked against the welcome.

The **token** is 8 hex digits, random, kept in the receiver's memory only, shown in the QR code and said by sound,
never put on the air or the network by the receiver. A sender that has it puts it in its hello:

```
sender   → {"t":"hello",...,"token":"0badf00d"}
receiver → {"t":"welcome",...,"token":"ok"|"bad"}          no "token" when the hello had none
```

`ok` proves the sender saw this TV's screen or heard it: it is the right TV, not one at the same address. A probe
does not use the token up; a real hello does, and the receiver shows a new one. On the plain port a valid token
lets the sender in without the Allow on the TV (section 3a): it was in the room. Pairing over TLS still asks for its
code, whose rounds keep a man in the middle out; the token, said aloud and shown to everyone, does not.

### 1f. The card

What a receiver says about itself where there is no room for JSON (1c, 1d), big-endian:

| Bytes | Field |
|---|---|
| 0..2 | `Lu` (0x4c 0x75) |
| 2 | `v << 4 \| kind`: protocol version (0); kind 1 = a receiver, 2 = a sender looking |
| 3..7 | the first 4 bytes of the id (its first 8 hex digits, dashes left out) |
| 7..11 | IPv4 (receiver only) |
| 11..13 | control port (receiver only) |
| 13..17 | pairing token (sound only) |

A card of another version, kind or magic is ignored; a receiver's card without an address too.

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

TCP from sender to the receiver's port: TLS 1.2 or 1.3 to the `tls` port (v1), or plain TCP to the announced port
(v0, older senders). UTF-8, one JSON object per line (`\n`), at most 64 KiB per line.
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
- On the TLS port the receiver answers `hello` with `welcome` (`"v":1`) when the sender is paired, else with the
  pairing of section 3a, and the `welcome` after it. On the plain port the `welcome` (`"v":0`, with `"tls_port"`)
  comes at once, but until the person allows that connection on the TV everything but `hello`, `ping`, `pong`,
  `bye` and `error` is answered `{"t":"error","code":"not_allowed","for":"<t>","detail":"..."}` (section 3a).
- `"probe":true` in a hello: discovery asking who is there (section 1b); the receiver answers and keeps nothing.

## 3a. Pairing (v1)

Both sides have a self-signed certificate, made once and kept: the receiver's key stays in the Android keystore, the
sender's in its own config directory, readable only by its user. There is no CA. Each side trusts the other for
being the certificate it *paired* with: it keeps the other's SHA-256 fingerprint (of the certificate's DER), and
that is all it checks. The receiver asks every TLS client for its certificate; the sender checks the receiver's
against its pin, and with no pin yet takes it only to pair.

```
sender   → {"t":"hello","v":1,...,"pair":true}         "pair": the sender has no pin for this receiver: pair even if known
receiver → {"t":"welcome","v":1,...}                    a paired sender: in, nothing on screen
         or {"t":"pair","step":"allow"}                 asking "allow?" on the TV first (something is playing)
         or {"t":"pair","step":"code","digits":6}       the code is on the TV (at once when idle, or after "allow")
         or {"t":"pair_refused","reason":R,"retry_s":N} then the connection is closed
sender   → {"t":"pair_commit","round":i,"c":"<hex>"}    one round per digit, i = 0..5
receiver → {"t":"pair_commit","round":i,"c":"<hex>"}
sender   → {"t":"pair_open","round":i,"n":"<hex nonce>"}
receiver → {"t":"pair_open","round":i,"n":"<hex nonce>"}   or pair_refused "wrong_code"
receiver → {"t":"paired"}, then {"t":"welcome",...}
```

`R` is `denied` (Deny on the TV), `dismissed` (Back), `timeout` (nobody answered in 60 s, or no code typed in
2 min), `wrong_code`, `banned` (`retry_s` until it is asked about again) or `busy` (another prompt is up or too many
came lately; `retry_s`).

**The code check.** The TV shows six digits; the person types them on the sender. A commitment is
`SHA-256("luch-pair-1" | role | round | digit | nonce | sender fp | receiver fp)`: `role` is the byte `S` or `R`,
`round` one byte, `digit` its ASCII byte, `nonce` 16 random bytes, the fingerprints 32 bytes each, as this side saw
them in the handshake. In every round both commit before either opens, and the sender opens first; a side that finds
the other's opening wrong stops (the receiver answers `wrong_code`). Both sides then pin. Someone in the middle shows
each side a certificate of its own, so the fingerprints differ on its two sides: it must commit to each digit before
it is opened to it, and gets past the six rounds once in 10^5 tries even if it learns one digit; it never gets a
whole-code hash to try all million codes on offline (section 8).

**On the TV.** A small card in the corner, over whatever plays, which nothing pauses: "Linux Laptop wants to
connect" with the code and Deny, or Allow and Deny while a picture, a video, photos or a document is shown (a
stranger cannot flash a code over a film; Allow then shows the code in the same card). Back closes it, which is not
a refusal. Without the "display over other apps" permission it is a notification with the same buttons.

**Limits.** A sender chooses its own `id` and key, so the receiver trusts neither alone. Refusals (Deny, a wrong
code) count against the key and against the address: after 3 within an hour, both are not asked about for an hour
(`banned`). One prompt at a time, and at most 5 in 10 minutes from all senders together (`busy`). At most 20
senders are paired; the least recently used one goes. A sender pairing again under its `id` with a new key replaces
its old key.

**Home and guest.** At home (the default) pairings stay. In guest mode they last one TV session: they are
forgotten when the TV goes to standby, and 24 hours after pairing in any case, so the next visit asks for the code
again. The status screen switches the mode and forgets all pairings.

**Older senders** (the plain port) cannot pair. Each of their connections is asked about with Allow and Deny (the
card says the sender has no encryption); Allow lets that connection in, nothing is remembered; refusals count
against the address as above. They get no crash reports and their media is not sealed. A sender that has paired
with a receiver never falls back to the plain port for it: no TLS where there was TLS is refused as another device
under that receiver's name.

## 4. Mirroring (v1)

Modelled on Sunshine and Moonlight: UDP, no jitter buffer, loss answered with a fresh IDR.

```
sender   → {"t":"start","mode":"mirror","video":{"codec":"video/hevc","width":1920,"height":1080,"fps":60},
            "audio":{"codec":"audio/opus","rate":48000,"channels":2,"frame_ms":5,"sync":false}}
receiver → {"t":"ready","video_port":P,"audio_port":Q,       or {"t":"error","code":"start_failed","detail":"..."}
            "crypt":"aes-128-gcm","key":"<base64, 16 bytes>"}  over TLS only: the media key of this session
receiver → {"t":"keyframe"}                                 an IDR is needed (start, or after a loss)
either   → {"t":"stop"} / receiver → {"t":"stop","by":"receiver","reason":"back"|"screen_closed"|"screen_not_shown"}
receiver → {"t":"stats","decode_ms":..,"host_ms":..,"fps":..,"lost":..,"dropped":..,"shown_seq":N,"decoder":"..",
            "audio_latency_ms":N,"video_hold_ms":N}
           may also carry "battery" (percent), "charging", "thermal"
           ("none|light|moderate|severe|critical|emergency|shutdown"), "power_save", "interactive"
sender   → {"t":"sender_stats","rtt_ms":..,"host_ms":..,"fps":..,"mbps":..,"encoder":"..","res":"WxH"}
```

The receiver ends a session itself with `reason` `back` (Back on the remote) or `screen_closed` (Home, another app
on top, or the screen staying off; on Android 8+ the session first goes on without the screen, muted, and ends with
`away_timeout` after 15 minutes if the screen does not come back). A TV whose screen is asleep is woken by `start`; the picture may be stopped for a
moment while the TV wakes up, so a stopped screen gets a second to come back before the session counts as closed,
and the receiver asks for a `keyframe` for the new surface. If the system will not open the screen at all (for 5 s
after Home on older Android, or without the "display over other apps" permission), the receiver asks again each
second and after 8 s ends the session with `screen_not_shown`.

**Audio modes.** No `audio`: picture only, the lowest latency. `audio` without `sync`: the sound plays as soon as
it is decoded, `audio_latency_ms` (stats) after the picture. `"sync":true`: the receiver holds each picture back by
that much (`video_hold_ms` in stats), so both come out together, at the cost of that much picture latency. On
Android 7+ the receiver cuts the audio buffer to about 20 ms and `sync` is not worth it; on Android 5 and 6 it
cannot (`limits` in the welcome, section 5), and the sender should let its user choose between the three.

The sender sends from a socket bound to the LAN interface (section 2); the receiver takes media only from the
address of the control connection (the same address family: IPv4 control, IPv4 media). `codec` should be
`bench.prefer` (section 5) when the sender can encode it.

**Sealed media (v1).** With a `key` in `ready`, every frame is sealed with AES-128-GCM as a whole before it is cut
into shards: the shards carry ciphertext, the last one ends with the 16-byte tag, and `frame length` counts the tag.
The nonce is `kind | 0 0 0 | frame sequence (u32) | 0 0 0 0`; the associated data is the 24-byte header with the
shard index zeroed and the parity bit cleared (the same for every shard of a frame). Audio is sealed the same way,
one packet a frame. The receiver opens a frame when its last shard is in, before anything in it counts: one that does
not open is a lost frame (an IDR is asked for), so a forged packet costs a glitch at most. A new key every session;
the sequence never repeats under one key.

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
  and the seek happens when the key is let go), up shows the controls, down opens the list of speeds, the menu key or a
  long OK opens speed, sound track and subtitles, Back stops. The controls hide after 3 s.
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

Reed-Solomon FEC shards (the header already has room; parity shards are made over the sealed frame); pairing with a
code shown on the TV (done, section 3a); `media` (done, section 4b); `{"t":"photo",...}` (done, section 4a: a
sender-served link, no inline data); `{"t":"key","code":...}` from the TV remote back to the sender (next slide,
pause); sender-served links over HTTPS with the paired certificates.

## 8. Design: who may use a receiver, and why so

**Threats.** A LAN is not a trusted place: a café or hotel Wi-Fi, a flat with guests, a neighbour's laptop on a
shared mesh. Before v1, anyone there could connect to a receiver and show anything, set the volume, open apps and
put it to sleep; anyone who opened luchd's web receiver saw the laptop's screen after one press. Wanted: nothing
reaches the screen without its person saying yes once; afterwards nothing to do; no prompt spam; no extra latency.

**How others do it.**

- *Moonlight / Sunshine (GameStream).* Moonlight shows a 4-digit PIN; the person types it into the host (Sunshine's
  web page). The client sends a salt and its certificate; both derive an AES key from `SHA-256(salt | PIN)` and trade
  encrypted challenges signed with their certificates; then each pins the other's certificate and later sessions
  use HTTPS with client certificates and no PIN. The control stream (ENet) is AES-GCM with a per-session key given
  over HTTPS at launch, audio AES-CBC; video encryption is optional (Sunshine: off on the LAN by default, on for
  WAN), as encrypting it per packet costs weak clients. Weak point: someone in the middle who sees the exchange can
  try all 10^4 PINs offline against it.
- *Android TV Remote v2 (Polo).* TLS with a self-signed certificate on each side; the TV shows a 6-hex-digit code;
  the client proves it by a hash over both certificates' keys and the code (the first byte doubles as a check). Both
  then pin. The same offline weakness: one hash, 16 secret bits.
- *AirPlay.* "Allow access: everyone / same network / home members / password", plus a 4-digit code on the screen
  for a new device. Pairing is SRP-6a (a PAKE, as in HomeKit): a man in the middle gets one online guess, nothing to
  try offline; then Ed25519 long-term keys, verified on every connection (pair-verify).
- *Chromecast.* No pairing on a network: anyone on the LAN can cast; only the receiver is authenticated (a device
  certificate from Google). Abuse is known (casting to TVs on campus and hotel networks); guest mode with a PIN was
  removed. *Miracast.* Wi-Fi Direct with WPS push-button or PIN; TVs show "Allow / Deny / Always", keep allowed and
  blocked lists, and some just accept anyone (the neighbour-casts-to-your-TV complaints).

**What Luch takes.** TLS with self-signed certificates pinned on both sides (Moonlight, Polo, AirPlay's long-term
keys); a code shown on the TV and typed on the sender (all of them); a prompt that does not interrupt what plays and
a code only after Allow when something does (Miracast's question, without its full-screen dialog); refusal limits
and a global pace of prompts (what Chromecast lacks). The code check avoids the offline weakness of Moonlight and
Polo without a PAKE, which would need elliptic-curve arithmetic by hand in a receiver without libraries: the digits
are committed to and opened one at a time, the sender first, as Bluetooth's passkey entry does with bits, so a man
in the middle must guess digits online, once per code shown, under the refusal limits.

**Media.** The UDP stream keeps its latency: no per-packet crypto and no jitter buffer added. A frame is sealed as a
whole (one AES-GCM call over ~30 KB for 1080p, up to ~200 KB for an IDR) and opened when its last shard is in, which
is when it would be decoded anyway. Measured on emulators of an ARM Mac: Android 14, 82 µs to open a 30 KB frame
and 132 µs for 200 KB; Android 6.0, 12 µs and 65 µs; Android 5.0 (an older, slower Conscrypt), 0.46 ms and 3.0 ms. A frame is ~16 ms at 60 fps, and
an IDR comes only at the start and after a loss; a real TV's numbers are the ones that count (the on-device test
`openingAFrameIsCheap` logs them). ring on the sender seals faster still. Unlike Sunshine's default this also covers the LAN:
here the LAN is the place the stream must be safe in, and per-frame sealing makes it cheap. Media is taken only
from the control connection's address (a stranger's packets are dropped before any work), and a forged frame
costs one lost frame at most.

**What is not covered yet.** Files, covers and relayed media that a receiver fetches from a sender (sections 4a,
4b, 4d, 4g) are plain HTTP on the LAN, protected by a random token in the path and by being served only to the
receiver's address. The receiver's own announcement is not authenticated (mDNS), so a first pairing trusts the
person to pair with the TV whose code they see.
