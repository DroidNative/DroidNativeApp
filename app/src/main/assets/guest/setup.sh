#!/bin/sh
# DroidNative first-boot setup, runs inside the guest as (fake) root.
# Usage: setup.sh <arch>    Idempotent: safe to run again after a failure.
set -eu
ARCH="$1"

log() { echo "[setup] $*"; }

# Base packages: graphics stack, test tools, Flatpak prerequisites, and the
# default applications (terminal, file manager, archiver, text editor,
# camera, music player, audio editor, video player; librsvg-utils renders
# their SVG icons for the overview, qt5-wayland lets SMPlayer use Wayland)
PKGS="vulkan-loader mesa-dri mesa-vulkan-lavapipe Vulkan-Tools mesa-demos
 libxkbcommon-tools xkeyboard-config wayland dbus bubblewrap ca-certificates
 fontconfig dejavu-fonts-ttf noto-fonts-ttf noto-fonts-emoji liberation-fonts-ttf
 cantarell-fonts adwaita-icon-theme hicolor-icon-theme glmark2 squashfs-tools
 pulseaudio pulseaudio-utils alsa-plugins-pulseaudio
 xfce4-terminal Thunar file-roller mousepad librsvg-utils v4l-utils
 cheese rhythmbox audacity smplayer mpv qt5-wayland
 gst-plugins-good1 gst-plugins-bad1 gst-plugins-ugly1 gst-libav
 bash bash-completion sudo qt6-wayland xorg-server-xwayland font-misc-misc"
# Required: setup fails without them (OctoXBPS drives the Install tab)
REQUIRED="octoxbps"
case "$ARCH" in
    aarch64) PKGS="$PKGS mesa-turnip-kgsl droidnative-tools gl4es flatpak droidnative-flatpak-gl" ;;
    x86_64) PKGS="$PKGS droidnative-tools gl4es flatpak" ;;
    i686|armv7l) PKGS="$PKGS droidnative-tools gl4es" ;;   # no Flathub builds
esac

log "updating xbps"
xbps-install -Sy xbps
log "updating base system"
xbps-install -uy
for p in $REQUIRED; do
    if ! xbps-query "$p" >/dev/null 2>&1; then
        log "installing $p (required)"
        xbps-install -y "$p"
    fi
done
for p in $PKGS; do
    if xbps-query "$p" >/dev/null 2>&1; then
        continue
    fi
    # droidnative-* come from the local repo, which may lack this arch
    if ! xbps-query -R "$p" >/dev/null 2>&1; then
        log "package $p not available for $ARCH, skipped"
        continue
    fi
    log "installing $p"
    xbps-install -y "$p"
done

# ALSA programs (Audacity through PortAudio) play through PulseAudio
if [ ! -e /etc/asound.conf ]; then
    printf 'pcm.!default pulse\nctl.!default pulse\n' > /etc/asound.conf
fi

log "building font cache"
fc-cache -f >/dev/null 2>&1 || true

log "generating keymap"
xkbcli compile-keymap --layout us > /run/droidnative/keymap.xkb.tmp
mv /run/droidnative/keymap.xkb.tmp /run/droidnative/keymap.xkb

mkdir -p /media/sdcard

# Unprivileged account that applications run as (uid matches Proot.kt)
if ! id user >/dev/null 2>&1; then
    log "creating user"
    useradd -m -u 1000 -U -s /bin/bash -G audio,video,input user
fi
mkdir -p /home/user
usermod -s /bin/bash user 2>/dev/null || true
# The user has no password (the phone is the authentication): sudo without one
printf 'user ALL=(ALL:ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/10-droidnative
chmod 440 /etc/sudoers.d/10-droidnative

# Installs made while everything ran as root move to the user's home
for d in .local/share/flatpak .var .local/share/droidnative .local/share/applications \
         .local/share/icons Applications; do
    if [ -e "/root/$d" ] && [ ! -e "/home/user/$d" ]; then
        log "moving /root/$d to /home/user"
        mkdir -p "/home/user/$(dirname "$d")"
        mv "/root/$d" "/home/user/$d"
    fi
done
log "done"
