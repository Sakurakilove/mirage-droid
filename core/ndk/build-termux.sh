#!/usr/bin/env bash
# build-termux.sh -- build qemu-system-arm natively in Termux (aarch64,
# bionic) for on-device testing.  This is the same QEMU source and the same
# bionic patches as the NDK cross build, only compiled by Termux's own clang
# against Termux's system glib/pixman, so it is the fastest way to exercise
# the machine model on real hardware before the CI artifact is available.
#
# Termux prerequisites:
#   pkg install clang binutils glib pixman ninja python
#   python3 -m pip install meson
#
# Usage: build-termux.sh [BUILD_DIR]
#   BUILD_DIR              out-of-tree build directory
#                          (default: <repo>/work/build-termux)
# Environment:
#   QEMU_SRC               qemu-miwear checkout
#                          (default: <repo>/work/refs/qemu-miwear)
#   JOBS                   parallel jobs (default: 3; Termux phones are RAM-bound)
#
# Result: <BUILD_DIR>/qemu-system-arm, which then answers
#   qemu-system-arm -M miwear,profile=help
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
QEMU_SRC="${QEMU_SRC:-$REPO/work/refs/qemu-miwear}"
BUILD="${1:-${BUILD_DIR:-$REPO/work/build-termux}}"
JOBS="${JOBS:-3}"

[ -d "$QEMU_SRC" ] || {
    echo "error: no QEMU source at $QEMU_SRC" >&2
    echo "       bash scripts/ci/fetch-qemu-src.sh $QEMU_SRC" >&2
    exit 1
}

command -v meson >/dev/null || { echo "error: install meson (pip install meson)" >&2; exit 1; }
command -v ninja >/dev/null || { echo "error: pkg install ninja" >&2; exit 1; }

"$HERE/apply-patches.sh" "$QEMU_SRC"

if [ ! -f "$BUILD/build.ninja" ]; then
    echo "== configuring in $BUILD"
    mkdir -p "$BUILD"
    (
        cd "$BUILD"
        "$QEMU_SRC/configure" \
            --target-list=arm-softmmu \
            --disable-werror \
            --disable-docs \
            --disable-tools \
            --disable-guest-agent \
            --disable-kvm \
            --disable-sdl \
            --disable-gtk \
            --disable-opengl \
            --disable-virglrenderer \
            --disable-slirp \
            --disable-capstone \
            --disable-dbus-display \
            --disable-plugins \
            > configure.log 2>&1 || {
                echo "error: configure failed; tail:" >&2
                tail -40 configure.log >&2
                exit 1
            }
    )
else
    echo "== reusing $BUILD (delete it for a clean build)"
fi

echo "== building qemu-system-arm with $JOBS jobs"
ninja -C "$BUILD" -j"$JOBS" qemu-system-arm > "$BUILD/build.log" 2>&1 || {
    echo "error: build failed; last errors:" >&2
    grep -E "FAILED|error:" "$BUILD/build.log" | tail -30 >&2
    exit 1
}

printf '\nbuilt: %s\n\n' "$BUILD/qemu-system-arm"
"$BUILD/qemu-system-arm" -M miwear,profile=help --display none 2>&1 | head -20 || true
