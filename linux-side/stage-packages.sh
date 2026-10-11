#!/usr/bin/env bash
# Copies the newest build of every DroidNative package from an xbps-src
# binpkgs directory into app/src/main/assets/repo/<arch>/ and re-indexes.
# build-packages.sh (Docker) does this itself; this is for host xbps-src
# builds. Obsolete versions are dropped by xbps-rindex -r.
#
# Usage: linux-side/stage-packages.sh <binpkgs-dir> [xbps-rindex]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$(cd "$1" && pwd)"
RINDEX="${2:-$ROOT/.cache/xbps-static/usr/bin/xbps-rindex}"
OUT="$ROOT/app/src/main/assets/repo"

# Main packages, plus subpackages listed in <template dir>/droidnative-stage
pkgs=()
for t in "$ROOT"/linux-side/srcpkgs/*/template; do
    dir="$(dirname "$t")"
    pkgs+=("$(basename "$dir")")
    if [ -f "$dir/droidnative-stage" ]; then
        while read -r sub; do
            case "$sub" in ''|'#'*) continue ;; esac
            pkgs+=("$sub")
        done < "$dir/droidnative-stage"
    fi
done

for arch in aarch64 armv7l i686 x86_64; do
    dest="$OUT/$arch"
    mkdir -p "$dest"
    copied=0
    for p in "${pkgs[@]}"; do
        # newest version first; skip -dbg packages (they live in binpkgs/debug)
        f="$(ls -t "$SRC"/"$p"-[0-9]*."$arch".xbps 2>/dev/null | head -1 || true)"
        [ -n "$f" ] || continue
        cp "$f" "$dest/"
        copied=$((copied + 1))
    done
    [ "$copied" -gt 0 ] || continue
    (
        cd "$dest"
        XBPS_TARGET_ARCH="$arch" "$RINDEX" -a ./*.xbps >/dev/null
        XBPS_TARGET_ARCH="$arch" "$RINDEX" -r "$dest" >/dev/null
    )
    echo "$arch: $(ls "$dest" | grep -c '\.xbps$') packages"
done
