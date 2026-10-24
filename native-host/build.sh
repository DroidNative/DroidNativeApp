#!/usr/bin/env bash
# Builds the bionic host executables that ship in the APK as lib*.so: apps
# targeting API 29+ may only execute files from their native library
# directory (docs/architecture.md). Output: app/src/main/jniLibs/<abi>/
#
# Usage: native-host/build.sh [abi...]
#        default: arm64-v8a armeabi-v7a x86_64 x86 (x86* for the emulator)
# Env:   ANDROID_NDK (default: <sdk.dir>/ndk/$NDK_VERSION)
#        DN_PROOT_SRC: build proot from this local checkout of the
#        DroidNative fork instead of the pinned commit (for proot work)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache"
OUT="$ROOT/app/src/main/jniLibs"
JOBS="$(nproc)"

NDK_VERSION="29.0.14206865"
API=24

# Pinned sources
# DroidNative fork of termux/proot (storage, V4L2, Flatpak support)
PROOT_REPO="https://github.com/DroidNative/proot.git"
PROOT_COMMIT="4ac94ecbb567608725e1d68d5ea617800a76c9c4"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
WAYLAND_VERSION="1.26.0"
WAYLAND_SHA256="64176eaa46e4969903e286f8e5ef8331affc17fdf03ac9b58381d2b23162b7a3"
LIBFFI_VERSION="3.8.0"
LIBFFI_SHA256="7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4"
# Disk images of the fork's storage extension (amalgamation, SHA3 checked
# against sqlite.org when pinned)
# virgl: OpenGL of the guest (Mesa virpipe) rendered with Android's GLES on
# GPUs without Turnip. Patches in patches/virglrenderer, taken from Termux's
# virglrenderer-android (termux-packages, packages/virglrenderer-android).
VIRGL_VERSION="1.3.0"
VIRGL_SHA256="56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"
EPOXY_VERSION="1.5.10"
EPOXY_SHA256="a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15"
SQLITE_VERSION="3540100"
SQLITE_SHA256="926ef8d3eab93fbf6e06011901273eec076b25594a4e1cdadd50c987f4f6d64e"

if [ -z "${ANDROID_NDK:-}" ]; then
    sdk="${ANDROID_SDK:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}"
    ANDROID_NDK="$sdk/ndk/$NDK_VERSION"
fi
[ -d "$ANDROID_NDK" ] || { echo "NDK not found: $ANDROID_NDK" >&2; exit 1; }
TC="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

fetch_sources() {
    mkdir -p "$CACHE"
    # A cache from before the fork points at termux/proot
    if [ -d "$CACHE/proot-src/.git" ]; then
        git -C "$CACHE/proot-src" remote set-url origin "$PROOT_REPO"
    else
        git clone -q "$PROOT_REPO" "$CACHE/proot-src"
    fi
    if ! git -C "$CACHE/proot-src" cat-file -e "$PROOT_COMMIT^{commit}" 2>/dev/null; then
        git -C "$CACHE/proot-src" fetch -q origin
    fi
    git -C "$CACHE/proot-src" checkout -q "$PROOT_COMMIT"

    if [ ! -d "$CACHE/talloc-$TALLOC_VERSION" ]; then
        local tgz="$CACHE/talloc-$TALLOC_VERSION.tar.gz"
        curl -sSfL -o "$tgz" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
        echo "$TALLOC_SHA256  $tgz" | sha256sum -c --quiet
        tar -xzf "$tgz" -C "$CACHE"
        rm "$tgz"
    fi
}

# $1 = url, $2 = sha256, $3 = directory name it unpacks to
fetch_tarball() {
    local url="$1" sum="$2" dir="$3"
    [ -d "$CACHE/$dir" ] && return 0
    local file="$CACHE/$(basename "$url")"
    curl -sSfL -o "$file" "$url"
    echo "$sum  $file" | sha256sum -c --quiet
    tar -xf "$file" -C "$CACHE"
    rm "$file"
}

fetch_sqlite_sources() {
    fetch_tarball "https://www.sqlite.org/2026/sqlite-autoconf-$SQLITE_VERSION.tar.gz" \
        "$SQLITE_SHA256" "sqlite-autoconf-$SQLITE_VERSION"
}

fetch_wayland_sources() {
    fetch_tarball "https://gitlab.freedesktop.org/wayland/wayland/-/releases/$WAYLAND_VERSION/downloads/wayland-$WAYLAND_VERSION.tar.xz" \
        "$WAYLAND_SHA256" "wayland-$WAYLAND_VERSION"
    fetch_tarball "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" \
        "$LIBFFI_SHA256" "libffi-$LIBFFI_VERSION"
}

# Host wayland-scanner from the same wayland release (system meson may be too
# old for wayland's own build, and distro scanners lag behind).
# Output: $CACHE/native-build/host/bin/wayland-scanner
build_host_scanner() {
    local src="$CACHE/wayland-$WAYLAND_VERSION"
    local hdir="$CACHE/native-build/host"
    [ -x "$hdir/bin/wayland-scanner" ] && return 0
    mkdir -p "$hdir/bin" "$hdir/gen"
    python3 "$src/src/embed.py" "$src/protocol/wayland.dtd" wayland_dtd > "$hdir/gen/wayland.dtd.h"
    cat > "$hdir/gen/config.h" <<CFG
#define PACKAGE_VERSION "$WAYLAND_VERSION"
CFG
    sed -e "s/@WAYLAND_VERSION_MAJOR@/${WAYLAND_VERSION%%.*}/" \
        -e "s/@WAYLAND_VERSION_MINOR@/$(echo "$WAYLAND_VERSION" | cut -d. -f2)/" \
        -e "s/@WAYLAND_VERSION_MICRO@/${WAYLAND_VERSION##*.}/" \
        -e "s/@WAYLAND_VERSION@/$WAYLAND_VERSION/" \
        "$src/src/wayland-version.h.in" > "$hdir/gen/wayland-version.h"
    cc -O2 -include "$hdir/gen/config.h" -I"$hdir/gen" -I"$src" -I"$src/src" \
        "$src/src/scanner.c" "$src/src/wayland-util.c" -lexpat \
        -o "$hdir/bin/wayland-scanner"
}

build_libffi() {
    local abi="$1" bdir="$2"
    local prefix="$bdir/prefix"
    [ -f "$prefix/lib/libffi.a" ] && return 0
    mkdir -p "$bdir/libffi"   # configure re-runs fine in an existing dir
    local host
    case "$abi" in
        arm64-v8a)   host=aarch64-linux-android ;;
        armeabi-v7a) host=armv7a-linux-androideabi ;;
        x86_64)      host=x86_64-linux-android ;;
        x86)         host=i686-linux-android ;;
    esac
    (cd "$bdir/libffi" && \
        CC="$TC/$TRIPLE-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
        CFLAGS="-O2 -fPIC" \
        "$CACHE/libffi-$LIBFFI_VERSION/configure" -q --host="$host" \
            --prefix="$prefix" --disable-shared --enable-static \
            --disable-docs --disable-multi-os-directory && \
        make -s -j"$JOBS" install >/dev/null)
}

# Static libwayland-server, compiled straight from the sources (no meson)
build_wayland_server() {
    local abi="$1" bdir="$2"
    local src="$CACHE/wayland-$WAYLAND_VERSION"
    local prefix="$bdir/prefix"
    local scanner="$CACHE/native-build/host/bin/wayland-scanner"
    local w="$bdir/wayland"
    mkdir -p "$w" "$prefix/include" "$prefix/lib"   # outputs are overwritten

    cat > "$w/config.h" <<CFG
#define HAVE_SYS_PRCTL_H 1
#define HAVE_ACCEPT4 1
#define HAVE_MKOSTEMP 1
#define HAVE_POSIX_FALLOCATE 1
#define HAVE_PRCTL 1
#define HAVE_MREMAP 1
#define HAVE_STRNDUP 1
#define HAVE_GETTID 1
#define HAVE_XUCRED_CR_PID 0
#define HAVE_BROKEN_MSG_CMSG_CLOEXEC 0
#define PACKAGE "wayland"
#define PACKAGE_VERSION "$WAYLAND_VERSION"
CFG
    cp "$CACHE/native-build/host/gen/wayland-version.h" "$w/"
    "$scanner" server-header "$src/protocol/wayland.xml" "$w/wayland-server-protocol.h"
    "$scanner" server-header -c "$src/protocol/wayland.xml" "$w/wayland-server-protocol-core.h"
    "$scanner" public-code "$src/protocol/wayland.xml" "$w/wayland-protocol.c"

    # Sources include "../config.h", so compile from a copy next to it
    mkdir -p "$w/src"
    cp -r "$src/src/." "$w/src/"
    local f objs=()
    for f in wayland-util.c wayland-os.c connection.c event-loop.c wayland-server.c wayland-shm.c; do
        "$TC/$TRIPLE-clang" -c -O2 -fPIC -std=gnu99 -D_GNU_SOURCE -fvisibility=hidden \
            -I"$w" -I"$w/src" -I"$prefix/include" \
            "$w/src/$f" -o "$w/${f%.c}.o"
        objs+=("$w/${f%.c}.o")
    done
    "$TC/$TRIPLE-clang" -c -O2 -fPIC -I"$src/src" -I"$w" "$w/wayland-protocol.c" -o "$w/wayland-protocol.o"
    objs+=("$w/wayland-protocol.o")
    rm -f "$prefix/lib/libwayland-server.a"
    "$TC/llvm-ar" rcs "$prefix/lib/libwayland-server.a" "${objs[@]}"

    for f in wayland-server.h wayland-server-core.h wayland-util.h; do
        cp "$src/src/$f" "$prefix/include/"
    done
    cp "$w/wayland-version.h" "$w/wayland-server-protocol.h" "$prefix/include/"
}

# $1 = abi -> sets TRIPLE (clang target prefix)
fetch_virgl_sources() {
    fetch_tarball "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz" \
        "$VIRGL_SHA256" "virglrenderer-virglrenderer-$VIRGL_VERSION"
    fetch_tarball "https://github.com/anholt/libepoxy/archive/refs/tags/$EPOXY_VERSION.tar.gz" \
        "$EPOXY_SHA256" "libepoxy-$EPOXY_VERSION"
    local src="$CACHE/virglrenderer-virglrenderer-$VIRGL_VERSION" p
    if [ ! -e "$src/.dn-patched" ]; then
        for p in "$ROOT"/native-host/patches/virglrenderer/*.patch; do
            patch -s -d "$src" -p1 < "$p"
        done
        touch "$src/.dn-patched"
    fi
}

# Meson cross file for the NDK toolchain of $TRIPLE
write_cross_file() {
    local abi="$1" file="$2" prefix="$3" cpu family
    case "$abi" in
        arm64-v8a)   family=aarch64; cpu=aarch64 ;;
        armeabi-v7a) family=arm; cpu=armv7 ;;
        x86_64)      family=x86_64; cpu=x86_64 ;;
        x86)         family=x86; cpu=i686 ;;
    esac
    cat > "$prefix/pkg-config" <<PKG
#!/bin/sh
PKG_CONFIG_DIR= PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" exec pkg-config --static "\$@"
PKG
    chmod +x "$prefix/pkg-config"
    cat > "$file" <<CROSS
[binaries]
c = '$TC/$TRIPLE-clang'
cpp = '$TC/$TRIPLE-clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkgconfig = '$prefix/pkg-config'

[built-in options]
c_args = ['-O2', '-fPIC']
c_link_args = ['-Wl,-z,max-page-size=16384']
default_library = 'static'

[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
CROSS
}

# meson setup that also works on an existing build dir (old meson knows
# --reconfigure only there)
meson_setup() {
    local dir="$1"; shift
    if [ -d "$dir/meson-private" ]; then
        meson setup --reconfigure "$dir" "$@"
    else
        meson setup "$dir" "$@"
    fi
}

# virgl_test_server, linked statically against virglrenderer and libepoxy;
# libepoxy dlopens Android's libEGL/libGLESv2 at run time
build_virgl() {
    local abi="$1" bdir="$2"
    local prefix="$bdir/prefix" cross="$bdir/meson-cross.txt"
    mkdir -p "$prefix"
    write_cross_file "$abi" "$cross" "$prefix"
    if [ ! -f "$prefix/lib/libepoxy.a" ]; then
        meson_setup "$bdir/epoxy-build" "$CACHE/libepoxy-$EPOXY_VERSION" \
            --cross-file "$cross" --prefix "$prefix" --libdir lib \
            -Degl=yes -Dglx=no -Dx11=false -Dtests=false >/dev/null
        ninja -C "$bdir/epoxy-build" install >/dev/null
    fi
    meson_setup "$bdir/virgl-build" "$CACHE/virglrenderer-virglrenderer-$VIRGL_VERSION" \
        --cross-file "$cross" --prefix "$prefix" --libdir lib \
        -Dplatforms=egl -Dvenus=false -Dtests=false -Dvideo=false >/dev/null
    ninja -C "$bdir/virgl-build" vtest/virgl_test_server >/dev/null
}

triple_for() {
    case "$1" in
        arm64-v8a)   TRIPLE="aarch64-linux-android$API" ;;
        armeabi-v7a) TRIPLE="armv7a-linux-androideabi$API" ;;
        x86_64)      TRIPLE="x86_64-linux-android$API" ;;
        x86)         TRIPLE="i686-linux-android$API" ;;
        *) echo "unsupported abi $1" >&2; exit 1 ;;
    esac
}

build_talloc() {
    local abi="$1" bdir="$2"
    local src="$CACHE/talloc-$TALLOC_VERSION"
    mkdir -p "$bdir/talloc/include"
    "$TC/$TRIPLE-clang" -c -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 \
        -I"$ROOT/native-host/talloc" -I"$src/lib/replace" -I"$src" \
        "$src/talloc.c" -o "$bdir/talloc/talloc.o"
    "$TC/$TRIPLE-clang" -c -O2 -fPIC "$ROOT/native-host/talloc/replace_min.c" \
        -o "$bdir/talloc/replace_min.o"
    rm -f "$bdir/talloc/libtalloc.a"
    "$TC/llvm-ar" rcs "$bdir/talloc/libtalloc.a" "$bdir/talloc/talloc.o" "$bdir/talloc/replace_min.o"
    cp "$src/talloc.h" "$bdir/talloc/include/"
}

build_proot() {
    local abi="$1" bdir="$2"
    if [ -n "${DN_PROOT_SRC:-}" ]; then
        # Local checkout, copied as it is (nothing is ever deleted here: stale
        # objects are rebuilt by make clean)
        [ -f "$DN_PROOT_SRC/src/GNUmakefile" ] || { echo "no proot tree in $DN_PROOT_SRC" >&2; exit 1; }
        mkdir -p "$bdir/proot-fork"
        rsync -a --exclude .git "$DN_PROOT_SRC/" "$bdir/proot-fork/"
        make -s -C "$bdir/proot-fork/src" clean >/dev/null 2>&1 || true
        PROOT_BUILD="$bdir/proot-fork"
    else
        # Per-ABI clone, reset to the pinned commit. git clean only ever
        # touches files inside that clone, so no recursive rm is needed.
        if [ ! -d "$bdir/proot/.git" ]; then
            git clone -q "$CACHE/proot-src" "$bdir/proot"
        fi
        git -C "$bdir/proot" fetch -q "$CACHE/proot-src" "$PROOT_COMMIT"
        git -C "$bdir/proot" reset -q --hard "$PROOT_COMMIT"
        git -C "$bdir/proot" clean -q -fdx
        PROOT_BUILD="$bdir/proot"
    fi
    local version="dn-${PROOT_COMMIT:0:12}"
    [ -n "${DN_PROOT_SRC:-}" ] && version="dn-fork-$(git -C "$DN_PROOT_SRC" rev-parse --short=12 HEAD 2>/dev/null || echo local)"
    # The loader path is always passed at runtime through PROOT_LOADER /
    # PROOT_LOADER_32, the compiled-in default is never used.
    # Flags go through the environment so the makefile's += still applies.
    CPPFLAGS="-I$bdir/talloc/include -DARG_MAX=131072 -DVERSION=\\\"$version\\\"" \
    LDFLAGS="-L$bdir/talloc -Wl,-z,max-page-size=16384" \
    make -s -C "$PROOT_BUILD/src" -j"$JOBS" proot \
        CC="$TC/$TRIPLE-clang" \
        STRIP="$TC/llvm-strip" \
        OBJCOPY="$TC/llvm-objcopy" \
        OBJDUMP="$TC/llvm-objdump" \
        PROOT_UNBUNDLE_LOADER=/nonexistent \
        SQLITE_DIR="$CACHE/sqlite-autoconf-$SQLITE_VERSION"
}

install_abi() {
    local abi="$1" bdir="$2"
    local dst="$OUT/$abi"
    mkdir -p "$dst"
    "$TC/llvm-strip" -o "$dst/libproot.so" "$PROOT_BUILD/src/proot"
    cp "$PROOT_BUILD/src/loader/loader" "$dst/libproot-loader.so"
    if [ -f "$PROOT_BUILD/src/loader/loader-m32" ]; then
        cp "$PROOT_BUILD/src/loader/loader-m32" "$dst/libproot-loader32.so"
    fi
    "$TC/llvm-strip" -o "$dst/libvirgl-server.so" "$bdir/virgl-build/vtest/virgl_test_server"
    chmod 755 "$dst"/lib*.so
}

main() {
    local abis=("$@")
    [ ${#abis[@]} -gt 0 ] || abis=(arm64-v8a armeabi-v7a x86_64 x86)
    fetch_sources
    fetch_sqlite_sources
    fetch_wayland_sources
    fetch_virgl_sources
    build_host_scanner
    for abi in "${abis[@]}"; do
        triple_for "$abi"
        local bdir="$CACHE/native-build/$abi"
        mkdir -p "$bdir"
        echo "== $abi"
        build_talloc "$abi" "$bdir"
        build_proot "$abi" "$bdir"
        build_virgl "$abi" "$bdir"
        install_abi "$abi" "$bdir"
        build_libffi "$abi" "$bdir"
        build_wayland_server "$abi" "$bdir"
    done
    ls -l "$OUT"/*/
}

main "$@"
