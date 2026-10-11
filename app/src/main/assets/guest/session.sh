#!/bin/sh
# DroidNative session daemons, run inside the guest by the app for as long as
# the environment is up: D-Bus session bus, PulseAudio, the notification and
# media player relay, Xwayland. Stays in the
# foreground; the app stops everything by ending the proot process.
#
# PulseAudio plays into a FIFO (module-pipe-sink); the app reads it and
# plays the PCM through Android's AudioTrack (see AudioOutput.kt).
RUN="${XDG_RUNTIME_DIR:-/run/droidnative}"
mkdir -p "$RUN/pulse"
rm -f "$RUN/bus" "$RUN/pulse/native" "$RUN/pulse/pid"

dbus-daemon --config-file=/etc/droidnative/dbus-session.conf \
    --address="unix:path=$RUN/bus" --nofork --nopidfile &
DBUS_PID=$!

# Notifications and media players go to the app (dn-notifyd, bridge.sock)
if command -v dn-notifyd >/dev/null; then
    for _ in $(seq 50); do [ -S "$RUN/bus" ] && break; sleep 0.1; done
    DBUS_SESSION_BUS_ADDRESS="unix:path=$RUN/bus" dn-notifyd &
    NOTIFY_PID=$!
fi

# -n: only our script, no system defaults (no udev/ALSA/bluetooth here)
pulseaudio -n -F /etc/droidnative/pulse.pa --daemonize=no --exit-idle-time=-1 \
    --disallow-exit --disable-shm=yes --log-target=stderr &
PULSE_PID=$!

# Microphone passthrough (DN_MIC=1 from the app): a source fed by the app
# through a FIFO, and mic.active telling the app whether anything records,
# so the phone's microphone is only open while needed. See
# docs/device-passthrough.md.
mic_watch() {
    for _ in $(seq 100); do pactl info >/dev/null 2>&1 && break; sleep 0.1; done
    rm -f "$RUN/mic.fifo"
    pactl load-module module-pipe-source source_name=android_mic file="$RUN/mic.fifo" \
        format=s16le rate=48000 channels=1 \
        source_properties=device.description=Android-Microphone >/dev/null || return
    pactl set-default-source android_mic
    idx=$(pactl list short sources | awk '$2 == "android_mic" { print $1 }')
    update() {
        n=$(pactl list short source-outputs 2>/dev/null | awk -v s="$idx" '$2 == s' | wc -l)
        [ "$n" -gt 0 ] && v=1 || v=0
        echo "$v" > "$RUN/mic.active.tmp" && mv -f "$RUN/mic.active.tmp" "$RUN/mic.active"
    }
    update
    pactl subscribe | while read -r line; do
        case "$line" in *source-output*) update ;; esac
    done
}
echo 0 > "$RUN/mic.active"
if [ "${DN_MIC:-0}" = 1 ]; then
    mic_watch &
    MIC_PID=$!
fi

# X11 programs: rootless Xwayland with dn-xwm as window manager (display :0)
if command -v dn-xwm >/dev/null && command -v Xwayland >/dev/null; then
    rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
    mkdir -p /tmp/.X11-unix
    chmod 1777 /tmp/.X11-unix 2>/dev/null || true
    dn-xwm :0 &
    XWM_PID=$!
fi

echo "session: dbus=$DBUS_PID pulseaudio=$PULSE_PID xwm=${XWM_PID:-none} notify=${NOTIFY_PID:-none}"
trap 'kill $DBUS_PID $PULSE_PID ${MIC_PID:-} ${XWM_PID:-} ${NOTIFY_PID:-} 2>/dev/null' INT TERM
wait
