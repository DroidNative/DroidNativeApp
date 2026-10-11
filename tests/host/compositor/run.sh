#!/usr/bin/env bash
# Host tests for the compositor core (null renderer, ASan/UBSan build).
# Runs dn-wltest in shm and dmabuf mode, plus weston-simple-shm if present.
# Usage: tests/host/compositor/run.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BUILD="$ROOT/.cache/host-test"
cmake -S "$ROOT/tests/host/compositor" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Debug >/dev/null
ninja -C "$BUILD" >/dev/null

# Unix socket paths are limited to 108 bytes, so keep this directory short
RUNDIR="$(mktemp -d /tmp/dnc-test.XXXXXX)"
LOG="$RUNDIR/compositor.log"
ERR="$RUNDIR/compositor.err"
SOCK="$RUNDIR/wayland-dn"
FAIL=0
EXPECT_CLOSED=2

cleanup() {
    [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null
    # Explicit names only, never a glob on a variable
    rm -f "${LOG:?}" "${ERR:?}" "${SOCK:?}"
    rmdir "${RUNDIR:?}"
}
trap cleanup EXIT

"$BUILD/dnc-headless" "$SOCK" > "$LOG" 2> "$ERR" &
PID=$!
for _ in $(seq 50); do [ -S "$SOCK" ] && break; sleep 0.1; done
[ -S "$SOCK" ] || { echo "FAIL compositor did not start"; cat "$ERR"; exit 1; }

export XDG_RUNTIME_DIR="$RUNDIR" WAYLAND_DISPLAY="wayland-dn"

check() {   # name, command...
    local name="$1"; shift
    if out="$("$@" 2>&1 | head -c 4096)"; then
        echo "PASS $name  $(echo "$out" | tail -1)"
    else
        echo "FAIL $name  $out"
        FAIL=1
    fi
}

check "shm 120 frames"    "$BUILD/dn-wltest" --mode shm --frames 120 --timeout-ms 10000
check "dmabuf 120 frames" "$BUILD/dn-wltest" --mode dmabuf --frames 120 --timeout-ms 10000
if command -v weston-simple-shm >/dev/null; then
    # Exits non-zero on SIGINT; its frames are checked through the stats below
    timeout -s INT 2 weston-simple-shm >/dev/null 2>&1 || true
    echo "RAN  weston-simple-shm 2s"
    EXPECT_CLOSED=3
fi

sleep 0.3
kill "$PID"; wait "$PID" 2>/dev/null || true
PID=

# Every closed toplevel must have presented frames with non-black content
closed=$(grep -c '"event":"closed"' "$LOG" || true)
bad=$(grep '"event":"closed"' "$LOG" | grep -Ec '"presented":0|"nonblack":0' || true)
echo "compositor: $closed toplevels closed, $bad without content"
[ "$closed" -ge "$EXPECT_CLOSED" ] && [ "$bad" -eq 0 ] || FAIL=1
if grep -E "ERROR: (Address|Undefined|Leak)Sanitizer|runtime error" "$ERR"; then
    echo "FAIL sanitizer reported problems"
    FAIL=1
fi
grep '"event":"closed"' "$LOG"

[ "$FAIL" -eq 0 ] && echo "ALL PASSED" || { echo "SOME TESTS FAILED"; exit 1; }
