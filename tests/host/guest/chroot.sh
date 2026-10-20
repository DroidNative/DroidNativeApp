#!/usr/bin/env bash
# Runs a command in an extracted Void rootfs on a Linux PC, emulating the
# guest layout DroidNative sets up with proot on the phone (see Proot.kt):
#   /run/droidnative          <- $RUNDIR (Wayland socket, keymap)
#   /dev /proc /sys           <- host
# The DroidNative repository is $DN_XBPS_REPO (default: the published one),
# trusted keys come from app/src/main/assets/xbps-keys and $DN_XBPS_KEY.
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
printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n' > "$RFS/etc/resolv.conf.dn"
mv "$RFS/etc/resolv.conf.dn" "$RFS/etc/resolv.conf"
mkdir -p "$RFS/etc/xbps.d" "$RFS/etc/profile.d" "$RFS/usr/libexec/droidnative" \
         "$RFS/run/droidnative" "$RFS/media/sdcard" "$RFS/var/db/xbps/keys"
echo "repository=${DN_XBPS_REPO:-https://github.com/DroidNative/xbps-repository/releases/download/current}" \
    > "$RFS/etc/xbps.d/10-droidnative.conf"
for k in "$ROOT"/app/src/main/assets/xbps-keys/*.plist ${DN_XBPS_KEY:-}; do
    if [ -f "$k" ]; then cp "$k" "$RFS/var/db/xbps/keys/"; fi
done
cp "$ROOT/app/src/main/assets/guest/profile.sh" "$RFS/etc/profile.d/droidnative.sh"
cp "$ROOT/app/src/main/assets/guest/setup.sh" "$RFS/usr/libexec/droidnative/setup.sh"
chmod 755 "$RFS/usr/libexec/droidnative/setup.sh"

exec unshare -r -m --fork -- /bin/sh -c '
    set -e
    RFS="$1"; RUNDIR="$2"; shift 2
    for d in dev sys; do mount --rbind "/$d" "$RFS/$d"; done
    mount --rbind /proc "$RFS/proc"
    mount --bind "$RUNDIR" "$RFS/run/droidnative"
    exec chroot "$RFS" /usr/bin/env -i HOME=/root USER=root TERM="${TERM:-xterm}" \
        LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin \
        XDG_RUNTIME_DIR=/run/droidnative WAYLAND_DISPLAY=wayland-0 \
        ${DN_EXTRA_ENV:-} "$@"
' dn-chroot "$RFS" "$RUNDIR" "$@"
