#!/usr/bin/env bash
# dn-notifyd on the PC: notifications and an MPRIS player relayed over the
# bridge socket. Runs a private session bus inside the PC guest of
# .cache/hostproot (never the desktop's bus); fake-app.py plays the app.
# Needs glib-devel, json-glib-devel and python3-gobject in that guest.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/tests/host/notify"
RFS="$ROOT/.cache/hostenv/rootfs-x86_64"
RUN="$ROOT/.cache/hostenv/run"   # the guest's /run/droidnative
OUT="$ROOT/.cache/hostenv/notify-test"
mkdir -p "$RUN" "$OUT" "$RFS/tmp/dn-build"
cp "$ROOT/guest-tools/dn-notifyd.c" "$HERE/fake-player.py" "$RFS/tmp/dn-build/"

python3 "$HERE/fake-app.py" "$RUN/bridge.sock" "$OUT/bridge.log" &
APP=$!
trap 'kill $APP 2>/dev/null || true' EXIT

"$ROOT/.cache/hostproot/run.sh" '
set -e
cd /tmp/dn-build
gcc -std=gnu99 -O2 -Wall -Wextra -o dn-notifyd dn-notifyd.c $(pkg-config --cflags --libs gio-unix-2.0 json-glib-1.0)
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/droidnative/bus
rm -f /run/droidnative/bus
dbus-daemon --session --address=$DBUS_SESSION_BUS_ADDRESS --nofork --nopidfile & BUS=$!
for i in $(seq 50); do [ -S /run/droidnative/bus ] && break; sleep 0.1; done
./dn-notifyd & D=$!
sleep 1
echo "action: $(notify-send -a Tester -A open=Open "Action test" "Body <b>bold</b>")"
notify-send -a Tester -h string:desktop-entry:mousepad -h int:urgency:2 "Hint test" "x"
gdbus call --session -d org.freedesktop.Notifications -o /org/freedesktop/Notifications \
    -m org.freedesktop.Notifications.CloseNotification 2 >/dev/null
notify-send -t 300 "Expire test" "gone soon"
dbus-monitor --session "interface=org.freedesktop.Notifications,member=NotificationClosed" > /tmp/dn-build/closed.log & MON=$!
sleep 0.3
notify-send "Dismiss test" "x"
sleep 1
python3 fake-player.py 6 > /tmp/dn-build/player.log
sleep 0.5
kill $MON $D $BUS 2>/dev/null || true
' 2>&1 | grep -v "proot warning" > "$OUT/guest.log" || true
wait $APP 2>/dev/null || true

fail=0
check() { if grep -qE -- "$2" "$3"; then echo "PASS $1"; else echo "FAIL $1"; fail=1; fi; }
B="$OUT/bridge.log"
P="$RFS/tmp/dn-build/player.log"
check "hello" '"type":"hello","version":1' "$B"
check "notify with action" '"title":"Action test".*"actions":\[\["open","Open"\]\]' "$B"
check "markup passed through" 'Body <b>bold</b>' "$B"
check "action reaches the client" '^action: open' "$OUT/guest.log"
check "hints" '"urgency":2,"desktop":"mousepad"' "$B"
check "close by call" '"type":"close","id":2' "$B"
check "expiry closes" '"type":"close","id":3' "$B"
check "dismiss signals client" 'uint32 4' "$RFS/tmp/dn-build/closed.log"
check "player state" '"type":"player","name":"org.mpris.MediaPlayer2.dntest".*"status":"Playing".*"artist":"Max Mustermann, demouser","title":"Test song 1","album":"Test album"' "$B"
check "length and position" '"length":180000000,.*"position":1000000' "$B"
check "playpause reaches player" '^call PlayPause' "$P"
check "seek reaches player" "call SetPosition \('/org/dntest/track/1', 42000000\)" "$P"
check "paused state" '"status":"Paused"' "$B"
check "seeked position" '"position":42000000' "$B"
check "next track" '"title":"Test song 2"' "$B"
check "player gone" '"type":"player-gone","name":"org.mpris.MediaPlayer2.dntest"' "$B"
[ $fail = 0 ] || { echo "--- guest"; head -c 4000 "$OUT/guest.log"; echo "--- bridge"; head -c 6000 "$B"; }
rm -f "$RFS/tmp/dn-build/closed.log" "$P" "$RUN/bridge.sock"
exit $fail
