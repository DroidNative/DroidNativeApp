#!/usr/bin/env bash
# Runs a shell command in the guest from inside the real app process (through
# the instrumentation), so it sees the app's SELinux domain and seccomp
# filter. Slower than guest-sh.sh (adb run-as) but faithful.
#
# Usage: tests/device/app-sh.sh [-p package] [-s serial] [-t seconds] [-d] '<command>'
#        -d: start the device passthrough bridges (cameras, sensors, ...)
set -euo pipefail

PKG=app.droidnative
ADB=(adb)
TIMEOUT=300
DEVICES=0
while getopts "p:s:t:d" opt; do
    case "$opt" in
        p) PKG="$OPTARG" ;;
        s) ADB=(adb -s "$OPTARG") ;;
        t) TIMEOUT="$OPTARG" ;;
        d) DEVICES=1 ;;
        *) exit 2 ;;
    esac
done
shift $((OPTIND - 1))
[ $# -eq 1 ] || { echo "usage: $0 [-p pkg] [-s serial] [-t seconds] [-d] '<command>'" >&2; exit 2; }

CMD64="$(printf '%s' "$1" | base64 -w0)"
"${ADB[@]}" shell am instrument -w -e class app.droidnative.test.GuestCommand \
    -e cmd64 "$CMD64" -e timeout "$TIMEOUT" -e devices "$DEVICES" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" |
    sed -n '/^--- exit=/,/^--- end/p'
