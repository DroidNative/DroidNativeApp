#!/usr/bin/env bash
# Runs a command in an extracted Void rootfs on a Linux PC, emulating the
# guest layout DroidNative sets up with proot on the phone (see Proot.kt):
#   /run/droidnative          <- $RUNDIR (Wayland socket, keymap)
#   /opt/droidnative/repo     <- app/src/main/assets/repo
#   /dev /proc /sys           <- host
# Uses an unprivileged user namespace (unshare -r) instead of proot.
#
# Usage: tests/host/guest/chroot.sh <rootfs> <rundir> <command...>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
RFS="$(cd "$1" && pwd)"
RUNDIR="$(cd "$2" && pwd)"
shift 2

[ -x "$RFS/usr/bin/env" ] || { echo "not a rootfs: $RFS" >&2; exit 1; }

# Host-side files the app writes in GuestSetup.writeHostFiles()
ARCH="$(uname -m)"
printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n' > "$RFS/etc/resolv.conf.dn"
mv "$RFS/etc/resolv.conf.dn" "$RFS/etc/resolv.conf"
mkdir -p "$RFS/etc/xbps.d" "$RFS/etc/profile.d" "$RFS/usr/libexec/droidnative" \
         "$RFS/run/droidnative" "$RFS/opt/droidnative/repo" "$RFS/media/sdcard"
echo "repository=/opt/droidnative/repo/$ARCH" > "$RFS/etc/xbps.d/10-droidnative-local.conf"
cp "$ROOT/app/src/main/assets/guest/profile.sh" "$RFS/etc/profile.d/droidnative.sh"
cp "$ROOT/app/src/main/assets/guest/setup.sh" "$RFS/usr/libexec/droidnative/setup.sh"
chmod 755 "$RFS/usr/libexec/droidnative/setup.sh"

exec unshare -r -m --fork -- /bin/sh -c '
    set -e
    RFS="$1"; RUNDIR="$2"; REPO="$3"; shift 3
    for d in dev sys; do mount --rbind "/$d" "$RFS/$d"; done
    mount --rbind /proc "$RFS/proc"
    mount --bind "$RUNDIR" "$RFS/run/droidnative"
    mount --bind "$REPO" "$RFS/opt/droidnative/repo"
    exec chroot "$RFS" /usr/bin/env -i HOME=/root USER=root TERM="${TERM:-xterm}" \
        LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin \
        XDG_RUNTIME_DIR=/run/droidnative WAYLAND_DISPLAY=wayland-0 \
        ${DN_EXTRA_ENV:-} "$@"
' dn-chroot "$RFS" "$RUNDIR" "$ROOT/app/src/main/assets/repo" "$@"
