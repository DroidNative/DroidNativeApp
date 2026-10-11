#!/usr/bin/env bash
# libdn-appenv on the PC: per-app settings and the child process policy.
# Installs the library and dn-gl from linux-side/srcpkgs/droidnative-tools
# into the PC guest of .cache/hostproot, then runs a fake application that
# starts a listed ("mousepad") and an unlisted ("printenv") program.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
RFS="$ROOT/.cache/hostenv/rootfs-x86_64"
RUN="$ROOT/.cache/hostenv/run"   # the guest's /run/droidnative
SRC="$ROOT/linux-side/srcpkgs/droidnative-tools/files"
mkdir -p "$RFS/usr/lib/droidnative" "$RUN"
gcc -std=gnu99 -O2 -Wall -shared -fPIC -o "$RFS/usr/lib/droidnative/libdn-appenv.so" "$SRC/dn-appenv.c" -ldl
install -m755 "$SRC/dn-gl" "$RFS/usr/bin/dn-gl"
# A listed program with its own backend and DPI; "mousepad" is faked by a
# script printing its environment
printf 'mousepad\tllvmpipe\t1.250\t-\n' > "$RUN/apps.list"
printf '#!/bin/sh\necho "$0: GALLIUM_DRIVER=${GALLIUM_DRIVER:-} MESA=${MESA_LOADER_DRIVER_OVERRIDE:-} GDK=${GDK_DPI_SCALE:-} PRELOAD=${LD_PRELOAD:-}"\n' > "$RFS/usr/local/bin/mousepad"
cp "$RFS/usr/local/bin/mousepad" "$RFS/usr/local/bin/other"
chmod 755 "$RFS/usr/local/bin/mousepad" "$RFS/usr/local/bin/other"

app() {   # app <policy>: the application is a shell started like LinuxApps.launch
    "$ROOT/.cache/hostproot/run.sh" "GDK_DPI_SCALE=0.800 DN_APP_VARS=GDK_DPI_SCALE DN_APP_CHILDREN=$1 \
        LD_PRELOAD=/usr/lib/droidnative/libdn-appenv.so \
        sh -c 'export DN_APP_PID=\$\$; exec dn-gl zink sh -c \"other; mousepad\"'" 2>&1 | grep -v "proot warning"
}

fail=0
check() { if grep -qF -- "$3" <<<"$2"; then echo "PASS $1"; else echo "FAIL $1"; echo "$2"; fail=1; fi; }

out="$(app none)"
check "none: unlisted child gets defaults" "$out" "other: GALLIUM_DRIVER= MESA= GDK= PRELOAD="
check "none: listed child gets its own settings" "$out" "mousepad: GALLIUM_DRIVER=llvmpipe MESA= GDK=1.250 PRELOAD="
out="$(app unlisted)"
check "unlisted: unlisted child keeps the app's" "$out" "other: GALLIUM_DRIVER= MESA=zink GDK=0.800 PRELOAD=/usr/lib/droidnative/libdn-appenv.so"
check "unlisted: listed child gets its own" "$out" "mousepad: GALLIUM_DRIVER=llvmpipe MESA= GDK=1.250 PRELOAD="
rm -f "$RUN/apps.list" "$RFS/usr/local/bin/mousepad" "$RFS/usr/local/bin/other"
exit $fail
