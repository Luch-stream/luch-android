#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Makes the receiver's decoder benchmark clips (android/app/src/main/assets/bench): 40 frames of 1080p60 test
# pattern per codec, encoded the way a live sender encodes (no B-frames, one IDR first), about 6 Mbit/s.
# H.264/HEVC as Annex-B with access unit delimiters; VP9/AV1 as IVF.
set -e
OUT="$(dirname "$0")/../android/app/src/main/assets/bench"
SRC="-f lavfi -i testsrc2=size=1920x1080:rate=60 -frames:v 40 -pix_fmt yuv420p"
ffmpeg -y -loglevel error $SRC -c:v libx264 -preset veryfast -tune zerolatency -bf 0 -g 999 -b:v 6M \
    -x264-params aud=1:repeat-headers=1 -f h264 "$OUT/avc.h264"
ffmpeg -y -loglevel error $SRC -c:v libx265 -preset veryfast -tune zerolatency -g 999 -b:v 6M \
    -x265-params bframes=0:aud=1:repeat-headers=1:log-level=error -f hevc "$OUT/hevc.h265"
ffmpeg -y -loglevel error $SRC -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -lag-in-frames 0 -g 999 -b:v 6M \
    -row-mt 1 -f ivf "$OUT/vp9.ivf"
ffmpeg -y -loglevel error $SRC -c:v libsvtav1 -preset 10 -g 999 -b:v 6M -svtav1-params pred-struct=1:rc=2 \
    -f ivf "$OUT/av1.ivf"
ls -la "$OUT"
