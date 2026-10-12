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

# Chromium sandbox switch: a fake browser directory (ICU data, V8 snapshot)
# with scripts that print their arguments, reached by path and through PATH
CHR="$RFS/opt/dn-test-chrome"
mkdir -p "$CHR"
: > "$CHR/icudtl.dat"
: > "$CHR/snapshot_blob.bin"
printf '#!/bin/sh\necho "${0##*/}: $*"\nexit 0\n' > "$CHR/chrome_crashpad_handler"
cp "$CHR/chrome_crashpad_handler" "$RFS/usr/local/bin/dn-test-plain"
# The browser binary counts only from 32 MiB on (sparse padding after exit)
cp "$CHR/chrome_crashpad_handler" "$CHR/chrome"
truncate -s 40M "$CHR/chrome"
chmod 755 "$CHR/chrome" "$CHR/chrome_crashpad_handler" "$RFS/usr/local/bin/dn-test-plain"
ln -sf /opt/dn-test-chrome/chrome "$RFS/usr/local/bin/dn-test-chrome"

guest() {   # guest <command>: like a program started anywhere in the guest
    "$ROOT/.cache/hostproot/run.sh" "LD_PRELOAD=/usr/lib/droidnative/libdn-appenv.so sh -c '$1'" 2>&1 |
        grep -v "proot warning"
}
check "chromium by path gets --no-sandbox" "$(guest '/opt/dn-test-chrome/chrome --a')" "chrome: --no-sandbox --a"
check "chromium through PATH and a symlink" "$(guest 'dn-test-chrome --a')" "chrome: --no-sandbox --a"
check "chromium switch not doubled" "$(guest 'dn-test-chrome --no-sandbox --a' | sed 's/^/=/')" "=dn-test-chrome: --no-sandbox --a"
check "chromium children unchanged" "$(guest 'dn-test-chrome --type=renderer' | sed 's/^/=/')" "=dn-test-chrome: --type=renderer"
check "small helper beside it unchanged" "$(guest '/opt/dn-test-chrome/chrome_crashpad_handler --a' | sed 's/^/=/')" "=chrome_crashpad_handler: --a"
check "other programs unchanged" "$(guest 'dn-test-plain --a' | sed 's/^/=/')" "=dn-test-plain: --a"
# As installed: listed in /etc/ld.so.preload, no LD_PRELOAD variable
echo /usr/lib/droidnative/libdn-appenv.so > "$RFS/etc/ld.so.preload"
check "chromium through /etc/ld.so.preload" "$("$ROOT/.cache/hostproot/run.sh" "sh -c 'dn-test-chrome --a'" 2>&1)" "chrome: --no-sandbox --a"
# Flatpak: an app with a Chromium binary gets the library for that run
FPA="$RFS/root/.local/share/flatpak/app"
FCH="$FPA/dn.test.Chrome/current/active/files/chromium"
FPL="$FPA/dn.test.Plain/current/active/files/bin"
mkdir -p "$FCH" "$FPL" "$RFS/opt/dn-test-bin"
: > "$FCH/icudtl.dat"
: > "$FCH/v8_context_snapshot.bin"
cp "$CHR/chrome" "$FCH/chrome"
cp "$CHR/chrome_crashpad_handler" "$FPL/plain"
cp "$CHR/chrome_crashpad_handler" "$RFS/opt/dn-test-bin/flatpak"
check "flatpak run of a Chromium app gets the preload" \
    "$(guest '/opt/dn-test-bin/flatpak run --branch=stable dn.test.Chrome --x')" \
    "flatpak: run --filesystem=host-os:ro --env=LD_PRELOAD=/run/host/usr/lib/droidnative/libdn-appenv.so --branch=stable dn.test.Chrome --x"
check "flatpak run of another app unchanged" "$(guest '/opt/dn-test-bin/flatpak run dn.test.Plain' | sed 's/^/=/')" \
    "=flatpak: run dn.test.Plain"
rm -f "$FCH/icudtl.dat" "$FCH/v8_context_snapshot.bin" "$FCH/chrome" "$FPL/plain" "$RFS/opt/dn-test-bin/flatpak"
for d in "$FCH" "$FPA/dn.test.Chrome/current/active/files" "$FPA/dn.test.Chrome/current/active" \
         "$FPA/dn.test.Chrome/current" "$FPA/dn.test.Chrome" "$FPL" "$FPA/dn.test.Plain/current/active/files" \
         "$FPA/dn.test.Plain/current/active" "$FPA/dn.test.Plain/current" "$FPA/dn.test.Plain" "$RFS/opt/dn-test-bin"; do
    rmdir "$d"
done
rm -f "$RFS/etc/ld.so.preload" "$CHR/icudtl.dat" "$CHR/snapshot_blob.bin" "$CHR/chrome" \
    "$CHR/chrome_crashpad_handler" "$RFS/usr/local/bin/dn-test-plain" "$RFS/usr/local/bin/dn-test-chrome"
rmdir "$CHR"
exit $fail
