#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""End-to-end test of media (PROTOCOL.md, section 4b) against a real receiver, playing the sender's part.

    tools/testmedia.py <receiver ip> [port] [--hls] [--keep] [--adb] [-v]

Makes a 30 s test clip with ffmpeg (its moving test pattern, two sound tracks in English and Russian) and WebVTT subtitles,
serves them over HTTP/1.1 with Range from this machine's LAN address, and checks step by step: a link that is not on
the sender is refused, the clip plays, pause, seek, speed, subtitles, the second sound track, seeking used Range, and
stop. --hls serves the clip as an HLS playlist instead of an MP4. --keep leaves it playing at the end. --adb goes
through adb instead of the network (adb forward for the control port, adb reverse for the files; ANDROID_SERIAL picks
the device), for a machine whose firewall keeps the receiver out. -v prints every HTTP request. Exit code 0 when every
step passed.
"""
import http.server
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time

args = [a for a in sys.argv[1:] if not a.startswith("--")]
HOST = args[0]
PORT = int(args[1]) if len(args) > 1 else 47800
HLS = "--hls" in sys.argv
ADB = "--adb" in sys.argv
KEEP = "--keep" in sys.argv
WORK = os.path.join(tempfile.gettempdir(), "luch-testmedia")
TOKEN = secrets.token_hex(8)
ranges = []


def make_media():
    os.makedirs(WORK, exist_ok=True)
    mp4 = os.path.join(WORK, "clip.mp4")
    if not os.path.exists(mp4):
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30:duration=30",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
            "-f", "lavfi", "-i", "sine=frequency=660:duration=30",
            "-map", "0:v", "-map", "1:a", "-map", "2:a",
            "-c:v", "libx264", "-preset", "veryfast", "-g", "30", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k",
            "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=rus",
            "-movflags", "+faststart", mp4], check=True)
    hls = os.path.join(WORK, "hls")
    if HLS and not os.path.exists(os.path.join(hls, "index.m3u8")):
        os.makedirs(hls, exist_ok=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp4, "-map", "0:v", "-map", "0:a:0",
                        "-c", "copy", "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
                        os.path.join(hls, "index.m3u8")], check=True)
    with open(os.path.join(WORK, "en.vtt"), "w") as f:
        f.write("WEBVTT\n\n")
        for s in range(0, 30, 2):
            f.write(f"00:{s:02d}.000 --> 00:{s + 1:02d}.800\nSecond {s}\n\n")


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WORK, **kw)

    def log_message(self, fmt, *a):
        if "-v" in sys.argv:
            print("  http:", fmt % a, file=sys.stderr)

    def translate_path(self, path):
        prefix = "/" + TOKEN + "/"
        if not path.startswith(prefix):
            return "/nonexistent"
        return super().translate_path("/" + path[len(prefix):])

    def do_GET(self):
        r = self.headers.get("Range")
        path = self.translate_path(self.path.split("?")[0])
        if not r or not os.path.isfile(path):
            return super().do_GET()
        ranges.append(r)
        size = os.path.getsize(path)
        m = re.match(r"bytes=(\d*)-(\d*)", r)
        start = int(m.group(1)) if m.group(1) else size - int(m.group(2))
        end = int(m.group(2)) if m.group(1) and m.group(2) else size - 1
        end = min(end, size - 1)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((HOST, 9))
    ip = s.getsockname()[0]
    s.close()
    return ip


make_media()
ME = "127.0.0.1" if ADB else lan_ip()
httpd = http.server.ThreadingHTTPServer((ME, 47811 if ADB else 0), Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
BASE = f"http://{ME}:{httpd.server_address[1]}/{TOKEN}/"
if ADB:
    # The receiver then sees the sender at 127.0.0.1, and the files are on 127.0.0.1 too: the same rule holds.
    subprocess.run(["adb", "reverse", "tcp:47811", "tcp:47811"], check=True, capture_output=True)
    subprocess.run(["adb", "forward", "tcp:47812", f"tcp:{PORT}"], check=True, capture_output=True)
    HOST, PORT = "127.0.0.1", 47812

ctl = socket.create_connection((HOST, PORT), timeout=5)
ctl.settimeout(None)
rf = ctl.makefile("r", encoding="utf-8")
inbox = []
lock = threading.Condition()


def reader():
    for line in rf:
        if line.strip():
            m = json.loads(line)
            if m.get("t") == "ping":
                send({"t": "pong", "n": m.get("n")})
                continue
            with lock:
                inbox.append(m)
                lock.notify_all()


def send(obj):
    ctl.sendall((json.dumps(obj) + "\n").encode())


def wait(pred, seconds=10):
    """The first message from now on that matches, or None."""
    until = time.time() + seconds
    with lock:
        start = len(inbox)
        while True:
            for m in inbox[start:]:
                if pred(m):
                    return m
            start = len(inbox)
            left = until - time.time()
            if left <= 0:
                return None
            lock.wait(left)


def state(pred, seconds=10):
    return wait(lambda m: m.get("t") == "media_state" and pred(m), seconds)


results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))


send({"t": "hello", "v": 0, "id": "testmedia", "name": "testmedia", "app": "testmedia.py", "want": ["media"]})
w = json.loads(rf.readline())
threading.Thread(target=reader, daemon=True).start()
check("welcome lists media", "media" in (w.get("modes") or []), w.get("modes"))
print("  receiver:", w.get("model"), "sdk", w.get("sdk"), "max", w.get("max"))

# 1. A link that is not on the sender is refused.
send({"t": "media", "via": "sender", "src": [{"url": "http://192.0.2.1/x.mp4", "mime": "video/mp4"}]})
e = wait(lambda m: m.get("t") in ("error", "media_ready"))
check("link off the sender refused", e is not None and e.get("code") == "bad_link", e)

# 2. Plays.
src = ({"url": BASE + "hls/index.m3u8", "mime": "application/vnd.apple.mpegurl"} if HLS else
       {"url": BASE + "clip.mp4", "mime": "video/mp4", "w": 1920, "h": 1080, "codec": "avc1.640028"})
send({"t": "media", "via": "sender", "title": "Luch test clip", "src": [
    {"url": BASE + "huge.mp4", "mime": "video/mp4", "w": 15360, "h": 8640, "codec": "hevc"}, src],
      "subs": [{"id": "en", "url": BASE + "en.vtt", "mime": "text/vtt", "lang": "en", "label": "English"}]})
r = wait(lambda m: m.get("t") in ("error", "media_ready"))
check("media_ready", r is not None and r.get("t") == "media_ready", r)
p = state(lambda m: m["state"] == "playing", 20)
check("playing", p is not None, inbox[-3:])
if p is None:
    sys.exit(1)
check("picked the source that fits (not the 16K one)", p.get("w") in (None, 1920), p)
a = state(lambda m: m["state"] == "playing" and m["pos"] >= p["pos"] + 3, 10)
check("position moves on", a is not None)
s = state(lambda m: m["tracks"]["sub"], 8) or a
subs = [t["id"] for t in s["tracks"]["sub"]] if s else []
check("subtitles listed", "en" in subs, s and s.get("tracks"))
audio = [t["id"] for t in s["tracks"]["audio"]] if s else []
check("sound tracks listed" + (" (HLS: one)" if HLS else ""), len(audio) >= (1 if HLS else 2), s and s.get("tracks"))
print("  tracks:", s and s["tracks"], "sel:", s and s["sel"], "dur:", s and s.get("dur"))
check("duration about 30 s", s is not None and s.get("dur") is not None and 29 <= s["dur"] <= 31, s and s.get("dur"))

# 3. Pause, and the position stays.
send({"t": "media_cmd", "do": "pause"})
q = state(lambda m: m["state"] == "paused")
check("pause", q is not None and q.get("by") == "sender", q)
time.sleep(1.5)
send({"t": "media_cmd", "do": "seek", "pos": 20})
q2 = state(lambda m: abs(m["pos"] - 20) < 1.5)
check("seek to 20 s while paused", q2 is not None and q2["state"] == "paused", q2)
n_ranges = len(ranges)

# 4. Play at 1.5x from 5 s.
send({"t": "media_cmd", "do": "seek", "pos": 5})
send({"t": "media_cmd", "do": "rate", "rate": 1.5})
send({"t": "media_cmd", "do": "play"})
r1 = state(lambda m: m["state"] == "playing" and abs(m["rate"] - 1.5) < 0.01)
check("play at 1.5x", r1 is not None, r1)
if r1:
    def sample():
        send({"t": "media_cmd", "do": "state"})
        m = state(lambda m: True, 5)
        return time.time(), m["pos"] if m else 0
    time.sleep(1)  # let the seek settle
    t0, pos0 = sample()
    time.sleep(4)
    t1, pos1 = sample()
    speed = (pos1 - pos0) / (t1 - t0)
    check("it really runs at about 1.5x", 1.35 <= speed <= 1.65, f"{speed:.2f}x")

# 5. Subtitles on, the other sound track.
send({"t": "media_cmd", "do": "sub", "id": "en"})
sb = state(lambda m: m["sel"]["sub"] == "en")
check("subtitles on", sb is not None, inbox[-1])
if len(audio) > 1:
    other = audio[1]
    send({"t": "media_cmd", "do": "audio", "id": other})
    au = state(lambda m: m["sel"]["audio"] == other)
    check("second sound track", au is not None, inbox[-1])
send({"t": "media_cmd", "do": "sub", "id": None})
so = state(lambda m: m["sel"]["sub"] is None)
check("subtitles off", so is not None)
if not HLS:
    check("seeking fetched with Range", len(ranges) >= 1, ranges[:3])
print("  Range requests:", len(ranges), "first:", ranges[:2])

# 6. Stop.
if not KEEP:
    send({"t": "stop"})
    time.sleep(1)
send({"t": "bye"})
print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
