#!/bin/bash
# Runs inside the build container. Args: <target-arch> <pkg...>
# /dn/root          the DroidNative repository (templates are read from
#                   linux-side/srcpkgs, results staged into app assets)
set -euo pipefail
arch="$1"; shift

for t in /dn/root/linux-side/srcpkgs/*/; do
    name="$(basename "$t")"
    mkdir -p "srcpkgs/$name"
    cp -r "$t/." "srcpkgs/$name/"   # overwrite, no recursive delete
done

# masterdir and hostdir live on docker volumes; bootstrap once
if [ ! -e masterdir/.xbps_chroot_init ]; then
    ./xbps-src -m masterdir binary-bootstrap
fi

# The image is x86_64: build natively for it, cross-compile everything else
cross=(-a "$arch")
[ "$arch" = x86_64 ] && cross=()
for pkg in "$@"; do
    if [ "${DN_CLEAN:-0}" = 1 ]; then
        ./xbps-src -m masterdir "${cross[@]}" clean "$pkg"
    fi
    ./xbps-src -m masterdir "${cross[@]}" -f pkg "$pkg"
done

# Stage the results into app/src/main/assets/repo (repo mounted at /dn/root)
/dn/root/linux-side/stage-packages.sh hostdir/binpkgs /usr/bin/xbps-rindex
