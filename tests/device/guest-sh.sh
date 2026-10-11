#!/usr/bin/env bash
# Runs a command inside the DroidNative guest on a connected phone, as the
# app user (adb run-as, debug builds only), with the same proot arguments as
# Proot.kt. For debugging; the rootfs must already be installed.
#
# Usage: tests/device/guest-sh.sh [-p package] [-s serial] '<shell command>'
#        DN_ENV="VAR=value ..." adds guest environment variables.
#        DN_PROOT_ARGS="-v 2" passes extra arguments to proot.
set -euo pipefail

PKG=app.droidnative
ADB=(adb)
while getopts "p:s:" opt; do
    case "$opt" in
        p) PKG="$OPTARG" ;;
        s) ADB=(adb -s "$OPTARG") ;;
        *) exit 2 ;;
    esac
done
shift $((OPTIND - 1))
[ $# -eq 1 ] || { echo "usage: $0 [-p pkg] [-s serial] '<command>'" >&2; exit 2; }

APK="$("${ADB[@]}" shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
[ -n "$APK" ] || { echo "$PKG not installed" >&2; exit 1; }
ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$ABI" in
    arm64-v8a) LIBARCH=arm64 ;;
    armeabi-v7a) LIBARCH=arm ;;
    x86_64) LIBARCH=x86_64 ;;
    x86) LIBARCH=x86 ;;
esac
LIB="$(dirname "$APK")/lib/$LIBARCH"
# /data/data is what the app sees as canonical (proot resolves symlinks);
# keep it identical here so emulated hard links work from both sides.
DATA="/data/data/$PKG"
F="$DATA/files"

# Quote the guest command for the device shell
CMD="$(printf '%q' "$1")"

"${ADB[@]}" shell run-as "$PKG" sh -c "'
export PROOT_LOADER=$LIB/libproot-loader.so PROOT_TMP_DIR=$DATA/cache/proot
export PROOT_L2S_DIR=$F/rootfs/.l2s
mkdir -p $F/rootfs/.l2s
[ -f $LIB/libproot-loader32.so ] && export PROOT_LOADER_32=$LIB/libproot-loader32.so
mkdir -p $DATA/cache/proot
exec $LIB/libproot.so ${DN_PROOT_ARGS:-} --kill-on-exit --link2symlink --sysvipc -L -0 \
  --rootfs=$F/rootfs --cwd=/root \
  --bind=/dev --bind=/proc --bind=/sys --bind=/dev/urandom:/dev/random \
  --bind=/proc/self/fd:/dev/fd \
  --bind=$F/sysdata/loadavg:/proc/loadavg --bind=$F/sysdata/stat:/proc/stat \
  --bind=$F/sysdata/uptime:/proc/uptime --bind=$F/sysdata/version:/proc/version \
  --bind=$F/sysdata/vmstat:/proc/vmstat \
  --bind=$F/sysdata/cap_last_cap:/proc/sys/kernel/cap_last_cap \
  --bind=$F/sysdata/max_user_watches:/proc/sys/fs/inotify/max_user_watches \
  --bind=$F/sysdata/overflowuid:/proc/sys/kernel/overflowuid \
  --bind=$F/sysdata/overflowgid:/proc/sys/kernel/overflowgid \
  --bind=$F/sysdata/empty:/sys/fs/selinux --bind=$F/shm:/dev/shm \
  --bind=$F/run:/run/droidnative --bind=$F/repo:/opt/droidnative/repo \
  /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 \
  TERM=xterm XDG_RUNTIME_DIR=/run/droidnative WAYLAND_DISPLAY=wayland-0 \
  ${DN_ENV:-} /bin/sh -c $CMD
'"
