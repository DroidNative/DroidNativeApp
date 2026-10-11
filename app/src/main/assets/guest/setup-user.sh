#!/bin/sh
# DroidNative per-user setup, runs inside the guest as the unprivileged user
# after setup.sh. Usage: setup-user.sh <arch>    Idempotent.
set -eu
ARCH="$1"

log() { echo "[setup] $*"; }

if command -v flatpak >/dev/null; then
    log "configuring flatpak"
    # Our drivers as an unmanaged GL extension; picked when the app's
    # environment has FLATPAK_GL_DRIVERS=droidnative (set by the app on KGSL
    # devices). "1.4" is the version every freedesktop runtime accepts.
    if [ -d /usr/lib/droidnative/flatpak-gl ]; then
        ext="$HOME/.local/share/flatpak/extension/org.freedesktop.Platform.GL.droidnative/$ARCH"
        mkdir -p "$ext"
        ln -sfn /usr/lib/droidnative/flatpak-gl "$ext/1.4"
    fi
    # Defaults for every app: our compositor and sound server, and the whole
    # /dev (the GPU is /dev/kgsl-3d0 plus ION/dma-heap, not /dev/dri).
    flatpak override --user --socket=wayland --socket=pulseaudio --device=all \
        --env=TU_DEBUG=noconform --env=PULSE_LATENCY_MSEC=150
fi
log "user setup done"
