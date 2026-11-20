#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test sender for Luch v1 mirroring: ffmpeg's test pattern as H.264 Annex-B, cut into PROTOCOL.md shards.

    tools/testsend.py <receiver ip> [seconds] [WxH] [oprate]

Only for checking a receiver without the Linux sender: no audio, IDR every 30 frames (ffmpeg cannot answer a
keyframe request), and the receiver's stats lines are printed as they come.
"""
import json
import socket
import struct
import subprocess
import sys
import threading
import time

HOST = sys.argv[1]
SECONDS = float(sys.argv[2]) if len(sys.argv) > 2 else 8
W, H = (int(x) for x in (sys.argv[3] if len(sys.argv) > 3 else "1920x1080").split("x"))
FPS = 60
OPRATE = len(sys.argv) > 4 and sys.argv[4] == "oprate"
SHARD = 1376

ctl = socket.create_connection((HOST, 47800), timeout=5)
rf = ctl.makefile("r", encoding="utf-8")


def send(obj):
    ctl.sendall((json.dumps(obj) + "\n").encode())


send({"t": "hello", "v": 0, "id": "testsend", "name": "testsend", "app": "testsend.py"})
print(rf.readline().strip()[:120])
send({"t": "start", "mode": "mirror", "video": {"codec": "video/avc", "width": W, "height": H, "fps": FPS, "operating_rate": OPRATE},
      "audio": {"codec": "audio/opus", "rate": 48000, "channels": 2, "frame_ms": 5}})
ready = None
while ready is None:
    m = json.loads(rf.readline())
    if m["t"] in ("ready", "error"):
        ready = m
print(ready)
if ready["t"] != "ready":
    sys.exit(1)
vport = ready["video_port"]


def reader():
    try:
        for line in rf:
            m = json.loads(line)
            if m["t"] in ("stats", "keyframe", "stop", "error"):
                print("<-", line.strip())
    except Exception:
        pass


threading.Thread(target=reader, daemon=True).start()

ff = subprocess.Popen(
    ["ffmpeg", "-loglevel", "error", "-re", "-f", "lavfi", "-i", f"testsrc2=size={W}x{H}:rate={FPS}",
     "-t", str(SECONDS), "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-bf", "0",
     "-g", "30", "-x264-params", "repeat-headers=1:aud=1", "-f", "h264", "-"],
    stdout=subprocess.PIPE)

udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
seq = 0
t0 = time.monotonic()
buf = b""
AUD = b"\x00\x00\x00\x01\x09"


def send_frame(au):
    global seq
    idr = any(((au[i + 4] if au[i:i + 4] == b"\x00\x00\x00\x01" else au[i + 3]) & 0x1f) == 5
              for i in range(len(au) - 4) if au[i:i + 3] == b"\x00\x00\x01" or au[i:i + 4] == b"\x00\x00\x00\x01")
    n = (len(au) + SHARD - 1) // SHARD
    pts = int((time.monotonic() - t0) * 1e6) & 0xffffffff
    for i in range(n):
        chunk = au[i * SHARD:(i + 1) * SHARD].ljust(SHARD, b"\x00")
        hdr = struct.pack(">BBHHHIIIHH", 1, 1 if idr else 0, i, n, 0, seq, pts, len(au), 0, 0)
        udp.sendto(hdr + chunk, (HOST, vport))
    seq += 1


while True:
    data = ff.stdout.read(65536)
    if not data:
        break
    buf += data
    # Access units start at each AUD (x264 aud=1); send every complete one.
    while True:
        start = buf.find(AUD)
        nxt = buf.find(AUD, start + 4) if start >= 0 else -1
        if start < 0 or nxt < 0:
            break
        send_frame(buf[start:nxt])
        buf = buf[nxt:]
if buf:
    send_frame(buf)
print("frames sent:", seq)
time.sleep(1.5)
send({"t": "stop"})
time.sleep(0.5)
