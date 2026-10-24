#!/usr/bin/env bash
# Builds the x86 test programs of the emulation check (dynamically linked
# against glibc, which box64/box86 replace with the native one).
# Output: hello-x86_64, hello-i386 next to this script. Needs gcc with -m32.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
gcc -O2 -o "$HERE/hello-x86_64" "$HERE/hello.c"
gcc -O2 -m32 -o "$HERE/hello-i386" "$HERE/hello.c"
file "$HERE"/hello-x86_64 "$HERE"/hello-i386
