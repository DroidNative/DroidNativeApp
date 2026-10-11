#!/usr/bin/env bash
# Builds DroidNative's Void packages (linux-side/srcpkgs/*) inside Docker and
# drops them as a local xbps repository into app/src/main/assets/repo/<arch>/,
# from where the app installs them into the rootfs.
#
# Usage: linux-side/build-packages.sh [-a arch] [-c] [pkg...]
#        default arch aarch64, default pkgs: every template in srcpkgs/
#        -c  clean each package's build and staging directories first
#            (needed after a failed build left them half done)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="droidnative/void-builder:latest"
ARCH=aarch64
CLEAN=0

while getopts "a:c" opt; do
    case "$opt" in
        a) ARCH="$OPTARG" ;;
        c) CLEAN=1 ;;
        *) echo "usage: $0 [-a arch] [-c] [pkg...]" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))

pkgs=("$@")
if [ ${#pkgs[@]} -eq 0 ]; then
    for t in "$ROOT"/linux-side/srcpkgs/*/template; do
        pkgs+=("$(basename "$(dirname "$t")")")
    done
fi

docker build -q -t "$IMAGE" "$ROOT/linux-side/docker" >/dev/null

docker run --rm --privileged -e DN_CLEAN="$CLEAN" \
    -v dn-void-masterdir:/home/builder/void-packages/masterdir \
    -v dn-void-hostdir:/home/builder/void-packages/hostdir \
    -v "$ROOT:/dn/root" \
    "$IMAGE" "$ARCH" "${pkgs[@]}"

ls -l "$ROOT/app/src/main/assets/repo/$ARCH"
