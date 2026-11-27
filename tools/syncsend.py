#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test sender for a group of Luch receivers in step (PROTOCOL.md 4h): one picture, silent sound, several receivers.

    tools/syncsend.py [--mode watch|interactive] [--delay MS] [--step-at S --step-to MS] [--seconds N] RECEIVER=ROLE...

RECEIVER is `emulator-5572` (reached through adb: the control port forwarded, the UDP ports redirected) or an IPv4
address with an optional `:port`. ROLE is video, audio or both. The first receiver shows the picture.

The picture is ffmpeg's test pattern (H.264, 1280x720, 30 fps). The sound is digital silence, the very packet the
Linux sender's Opus encoder makes for it: a receiver plays it, nothing is heard. The sender's clock is this
machine's monotonic clock; clock requests (kind 3) are answered on every media socket. D is written into every
packet and can step once (`--step-at`), to see every receiver resync on the same frame. Each receiver's stats are
printed once a second, and a summary at the end.
"""
import argparse
import json
import socket
import statistics
import struct
import subprocess
import threading
import time

SHARD = 1376
ADB = "adb"
# libopus, CELT 5 ms, 128 kbit/s CBR, stereo, of digital silence (PROTOCOL.md 4, audio).
SILENCE = bytes([0xEC, 0xFF, 0xFE]) + bytes(77)
OPUS_HEAD = b"OpusHead" + bytes([1, 2]) + struct.pack("<HIhB", 312, 48000, 0, 0)
OFFSET_US = 5_000_000_000   # the sender's clock is far from any receiver's: only a working estimate maps it


def now_us():
    return time.monotonic_ns() // 1000 + OFFSET_US


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Receiver:
    def __init__(self, spec, index):
        where, _, self.role = spec.partition("=")
        self.role = self.role or ("both" if index == 0 else "audio")
        self.name = where
        self.emulator = where.startswith("emulator-")
        if self.emulator:
            host_port = 47900 + index
            subprocess.run([ADB, "-s", where, "forward", f"tcp:{host_port}", "tcp:47800"], check=True)
            self.host, ctl = "127.0.0.1", host_port
        else:
            self.host, _, p = where.partition(":")
            ctl = int(p or 47800)
        self.ctl = socket.create_connection((self.host, ctl), timeout=10)
        self.rf = self.ctl.makefile("r", encoding="utf-8")
        self.lock = threading.Lock()
        self.stats = []
        self.stop = None
        self.socks = {}

    def send(self, obj):
        with self.lock:
            self.ctl.sendall((json.dumps(obj) + "\n").encode())

    def next(self, *types):
        while True:
            m = json.loads(self.rf.readline())
            if m["t"] == "ping":
                self.send({"t": "pong", "n": m.get("n")})
            elif m["t"] in types:
                return m

    def udp(self, kind, guest_port):
        """The socket a kind's packets go out on, and come back on (clock requests)."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if self.emulator:
            hp = free_port()
            subprocess.run([ADB, "-s", self.name, "emu", "redir", "add", f"udp:{hp}:{guest_port}"], check=True,
                           stdout=subprocess.DEVNULL)
            s.bind(("127.0.0.1", 0))
            to = ("127.0.0.1", hp)
        else:
            s.bind(("0.0.0.0", 0))
            to = (self.host, guest_port)
        self.socks[kind] = (s, to)
        threading.Thread(target=answer_clock, args=(s, self), daemon=True).start()

    def read(self):
        try:
            for line in self.rf:
                m = json.loads(line)
                if m["t"] == "ping":
                    self.send({"t": "pong", "n": m.get("n")})
                elif m["t"] == "stats":
                    self.stats.append((time.monotonic(), m))
                elif m["t"] == "stop":
                    self.stop = m
                elif m["t"] == "keyframe":
                    want_idr[0] = True
        except Exception:
            pass


want_idr = [False]
answered = {}


def answer_clock(s, rx):
    """PROTOCOL.md 4h: a request is answered at once with t2 (received) and t3 (sent) on the pts clock."""
    while True:
        try:
            b, addr = s.recvfrom(256)
        except OSError:
            return
        t2 = now_us()
        if len(b) < 32 or b[0] != 3 or b[1] & 16:
            continue
        reply = bytearray(b[:24]) + b[24:32] + struct.pack(">QQ", t2, now_us())
        reply[1] = 16
        reply[16:20] = struct.pack(">I", 24)
        s.sendto(bytes(reply), addr)
        answered[rx.name] = answered.get(rx.name, 0) + 1


def header(kind, flags, i, n, seq, pts, length, delay_units):
    return struct.pack(">BBHHHIIIHH", kind, flags, i, n, 0, seq & 0xffffffff, pts & 0xffffffff, length, 0,
                       delay_units)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("receivers", nargs="+")
    ap.add_argument("--mode", default="watch")
    ap.add_argument("--delay", type=float, default=250)
    ap.add_argument("--step-at", type=float, default=0)
    ap.add_argument("--step-to", type=float, default=0)
    ap.add_argument("--seconds", type=float, default=30)
    a = ap.parse_args()
    rxs = [Receiver(spec, i) for i, spec in enumerate(a.receivers)]
    group = {"id": "syncsend", "timeline": 7, "mode": a.mode, "tolerance_ms": 20}
    W, H, FPS = 1280, 720, 30
    audio = {"codec": "audio/opus", "rate": 48000, "channels": 2, "frame_ms": 5}
    for r in rxs:
        r.send({"t": "hello", "v": 0, "id": "syncsend", "name": "Linux Laptop", "app": "syncsend.py",
                "want": ["mirror"]})
        w = r.next("welcome")
        if not w.get("sync", {}).get("clock"):
            raise SystemExit(f"{r.name} does not announce sync")
        start = {"t": "start", "mode": "audio" if r.role == "audio" else "mirror",
                 "group": dict(group, role=r.role, offset_ms=0)}
        if r.role != "audio":
            start["video"] = {"codec": "video/avc", "width": W, "height": H, "fps": FPS}
        if r.role != "video":
            start["audio"] = audio
        r.send(start)
        ready = r.next("ready", "error")
        if ready["t"] != "ready":
            raise SystemExit(f"{r.name}: {ready}")
        if r.role != "audio":
            r.udp(1, ready["video_port"])
        if r.role != "video":
            r.udp(2, ready["audio_port"])
        threading.Thread(target=r.read, daemon=True).start()
        print(f"{r.name}: {r.role}, ready {ready}")

    d = [a.delay]
    t0 = time.monotonic()
    # Interactive: the picture at once, the sound at the later of the speakers' need and the TV's pts → display
    # (SenderPolicy / luchd's sync::Policy, rising at once; this test tool never lowers it).
    da, dv, vpts = [0.0], [0.0], [0.0]

    def interactive():
        while time.monotonic() - t0 < a.seconds:
            time.sleep(1)
            video = [r.stats[-1][1].get("out", {}).get("video_pts_ms") for r in rxs if r.role != "audio" and r.stats]
            need = [r.stats[-1][1].get("need_ms") for r in rxs if r.role != "video" and r.stats]
            video = [x for x in video if x is not None]
            if video and dv[0] == 0:
                vpts[0] = max(video)   # the picture's own pts → display, while it is not held
            target = max([x for x in [vpts[0]] + need if x is not None] or [0])
            if target > da[0] + 1:
                da[0] = target
                # The picture waits only for a speaker beyond the tolerance, by the excess.
                dv[0] = da[0] - 20 if da[0] > vpts[0] + 20 else 0
                print(f"--- D_a {da[0]:.1f} ms, D_v {dv[0]:.1f} ms")

    if a.mode == "interactive":
        threading.Thread(target=interactive, daemon=True).start()

    def units(kind):
        if a.mode == "interactive":
            return int(round((dv[0] if kind == 1 else da[0]) * 10))
        return int(round(d[0] * 10))

    def audio_loop():
        for r in rxs:
            if 2 in r.socks:
                s, to = r.socks[2]
                s.sendto(header(2, 2, 0, 1, 0, now_us(), len(OPUS_HEAD), units(2)) + OPUS_HEAD, to)
        seq, start = 0, now_us()
        while time.monotonic() - t0 < a.seconds:
            pts = start + seq * 5000
            for r in rxs:
                if 2 in r.socks:
                    s, to = r.socks[2]
                    s.sendto(header(2, 0, 0, 1, seq, pts, len(SILENCE), units(2)) + SILENCE, to)
            seq += 1
            wait = (pts + 5000 - now_us()) / 1e6
            if wait > 0:
                time.sleep(wait)

    threading.Thread(target=audio_loop, daemon=True).start()
    ff = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-re", "-f", "lavfi", "-i", f"testsrc2=size={W}x{H}:rate={FPS}",
         "-t", str(a.seconds), "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-bf", "0",
         "-g", str(FPS), "-x264-params", "repeat-headers=1:aud=1", "-f", "h264", "-"], stdout=subprocess.PIPE)
    aud = b"\x00\x00\x00\x01\x09"
    vseq, buf, stepped, printed = 0, b"", False, 0

    def send_video(au):
        nonlocal vseq
        idr = b"\x00\x00\x01\x65" in au or b"\x00\x00\x00\x01\x65" in au
        n = (len(au) + SHARD - 1) // SHARD
        pts = now_us()   # the capture time of this frame, as a sender stamps it
        for r in rxs:
            if 1 not in r.socks:
                continue
            s, to = r.socks[1]
            for i in range(n):
                chunk = au[i * SHARD:(i + 1) * SHARD]
                s.sendto(header(1, 1 if idr else 0, i, n, vseq, pts, len(au), units(1)) + chunk, to)
        vseq += 1

    while True:
        data = ff.stdout.read(65536)
        if not data:
            break
        buf += data
        while True:
            s = buf.find(aud)
            e = buf.find(aud, s + 4) if s >= 0 else -1
            if s < 0 or e < 0:
                break
            send_video(buf[s:e])
            buf = buf[e:]
        el = time.monotonic() - t0
        if a.step_at and not stepped and el >= a.step_at:
            stepped = True
            d[0] = a.step_to
            print(f"--- D {a.delay} → {a.step_to} ms at {el:.1f} s")
        if int(el) > printed:
            printed = int(el)
            for r in rxs:
                if r.stats:
                    print(f"{printed:3d}s {r.name}: {line(r.stats[-1][1])}")
    time.sleep(1.5)
    for r in rxs:
        r.send({"t": "stop"})
    print("\nsummary (from 10 s after the start, and after the step):")
    for r in rxs:
        steady = [m for (t, m) in r.stats if t - t0 > 10 and (not a.step_at or t - t0 < a.step_at or t - t0 > a.step_at + 3)]
        print(f"{r.name} ({r.role}), clock requests answered {answered.get(r.name, 0)}:")
        for key, get in [("clock err µs", lambda m: m.get("clock", {}).get("err_us")),
                         ("rtt µs", lambda m: m.get("clock", {}).get("rtt_us")),
                         ("sync_err ms (sound, or picture)", lambda m: m.get("sync_err_ms")),
                         ("video_err ms", lambda m: m.get("out", {}).get("video_err_ms")),
                         ("video pts→display ms", lambda m: m.get("out", {}).get("video_pts_ms")),
                         ("audio path ms", lambda m: m.get("out", {}).get("audio_ms")),
                         ("need ms", lambda m: m.get("need_ms"))]:
            v = [x for x in (get(m) for m in steady) if x is not None]
            if v:
                print(f"  {key:32s} median {statistics.median(v):9.2f}  min {min(v):9.2f}  max {max(v):9.2f}  n {len(v)}")
        last = steady[-1] if steady else {}
        o = last.get("out", {})
        print(f"  audio resyncs {o.get('audio_resyncs')}, concealed {o.get('audio_concealed')}, src {o.get('audio_src')}")


def line(m):
    c, o = m.get("clock", {}), m.get("out", {})
    return (f"clock {c.get('err_us')} µs rtt {c.get('rtt_us')} µs, need {m.get('need_ms')} ms, sync_err "
            f"{m.get('sync_err_ms')} ms, video err {o.get('video_err_ms')} pts→display {o.get('video_pts_ms')}, "
            f"audio {o.get('audio_ms')} ms {o.get('audio_src')}, resyncs {o.get('audio_resyncs')}")


if __name__ == "__main__":
    main()
