#!/usr/bin/env bash
# build-qemu-native.sh -- build qemu-system-arm (arm-softmmu) for the *build
# host* (x86_64 Linux), for firmware smoke tests and local development.  This
# is deliberately separate from the NDK cross build: firmware-smoke.yml runs
# this on ubuntu-latest, where glib/pixman come from the distribution.
#
# Usage: build-qemu-native.sh [OUTDIR]
#   OUTDIR                 output directory (default: $PWD/qemu-native);
#                          the emulator lands in <OUTDIR>/qemu-system-arm
# Environment:
#   QEMU_SRC               existing qemu-miwear checkout (default:
#                          <OUTDIR>/qemu-src, cloned on demand at the pin)
#   QEMU_CONFIGURE_EXTRA   extra ./configure arguments (word-splittable)
#   JOBS                   parallel build jobs (default: nproc)
#
# Host requirements: meson, ninja, pkg-config, a C compiler, glib2 and pixman
# development packages (Debian/Ubuntu: libglib2.0-dev libpixman-1-dev
# ninja-build pkg-config; add libpng-dev if you want QMP screendump PNGs).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT="${1:-$PWD/qemu-native}"
QEMU_SRC="${QEMU_SRC:-$OUT/qemu-src}"
WORK="$OUT/build"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

mkdir -p "$OUT"
if [ ! -d "$QEMU_SRC/.git" ]; then
    "$HERE/fetch-qemu-src.sh" "$QEMU_SRC"
fi

# shellcheck disable=SC2086
CONFIGURE_EXTRA="${QEMU_CONFIGURE_EXTRA:-}"

if [ ! -f "$WORK/build.ninja" ]; then
    echo "== configuring (native, out-of-tree)"
    rm -rf "$WORK"
    mkdir -p "$WORK"
    (
        cd "$WORK"
        "$QEMU_SRC/configure" \
            --target-list=arm-softmmu \
            --disable-werror \
            --disable-docs \
            --disable-tools \
            --disable-guest-agent \
            --disable-slirp \
            --disable-capstone \
            --disable-gtk \
            --disable-sdl \
            --disable-opengl \
            --disable-virglrenderer \
            $CONFIGURE_EXTRA \
            > configure.log 2>&1 || {
                echo "error: configure failed; tail:" >&2
                tail -60 configure.log >&2
                exit 1
            }
    )
else
    echo "== reusing $WORK (delete it for a clean build)"
fi

echo "== building with $JOBS jobs"
ninja -C "$WORK" -j"$JOBS" > "$WORK/build.log" 2>&1 || {
    echo "error: build failed; last errors:" >&2
    grep -E "FAILED|error:" "$WORK/build.log" | tail -40 >&2
    exit 1
}

cp -f "$WORK/qemu-system-arm" "$OUT/qemu-system-arm"
printf '\nbuilt: %s\n' "$OUT/qemu-system-arm"
"$OUT/qemu-system-arm" --version || true
