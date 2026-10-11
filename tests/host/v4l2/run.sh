#!/usr/bin/env bash
# Camera passthrough on the PC: PRoot's V4L2 emulation (dn_v4l2 extension)
# against feed.c, with v4l2-ctl from the guest as the client.
# Needs the host PRoot build and guest of .cache/hostproot (v4l-utils installed).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; ROOT="$(dirname "$ROOT")"
HP="$ROOT/.cache/hostproot"
DIR="$ROOT/.cache/hostenv/vdev"     # short: Unix socket paths end at 108 bytes
RUN="$ROOT/.cache/hostenv/run"
mkdir -p "$DIR"

cc -O1 -Wall -I"$HP/proot/src/extension/dn_v4l2" -o "$DIR/feed" "$ROOT/tests/host/v4l2/feed.c"
"$DIR/feed" "$DIR" 120 > "$DIR/feed.log" &
FEED=$!
trap 'kill $FEED 2>/dev/null || true' EXIT
for _ in $(seq 50); do [ -S "$DIR/video0.sock" ] && break; sleep 0.1; done

guest() {
    PROOT_DN_VIDEO_DIR="$DIR" DN_PROOT_ARGS="--bind=$DIR/video0:/dev/video9" "$HP/run.sh" "$1"
}

fail=0
check() {   # check <name> <command> <expected substring>
    local out
    out="$(guest "$2" 2>&1)" || true
    if grep -qF -- "$3" <<<"$out"; then echo "PASS $1"; else echo "FAIL $1"; echo "$out" | head -20; fail=1; fi
}

check "stat is a character device 81:0" "stat -c '%F %t:%T' /dev/video9" "character special file 51:0"
check "querycap" "v4l2-ctl -d /dev/video9 --info" "Test camera"
check "formats" "v4l2-ctl -d /dev/video9 --list-formats-ext" "1280x720"
check "set format" "v4l2-ctl -d /dev/video9 --set-fmt-video=width=320,height=240,pixelformat=NV12 --get-fmt-video" "115200"
# 10 frames of 640x480 YUYV; frame n is all bytes n, so the file size and
# the first byte of the last frame prove the copies
check "mmap streaming" "v4l2-ctl -d /dev/video9 --set-fmt-video=width=640,height=480,pixelformat=YUYV --stream-mmap --stream-count=10 --stream-to=/tmp/dn-frames.raw >/dev/null 2>&1; stat -c %s /tmp/dn-frames.raw; od -An -tu1 -j \$((614400*9)) -N1 /tmp/dn-frames.raw" "6144000"
check "frame content" "od -An -tu1 -j \$((614400*9+614399)) -N1 /tmp/dn-frames.raw" "9"
check "userptr streaming" "v4l2-ctl -d /dev/video9 --stream-user --stream-count=5 --stream-to=/tmp/dn-frames.raw >/dev/null 2>&1; stat -c %s /tmp/dn-frames.raw" "3072000"
sleep 0.5
grep -q "stop after" "$DIR/feed.log" && echo "PASS host saw stop" || { echo "FAIL host saw stop"; fail=1; }
cat "$DIR/feed.log"
exit $fail
