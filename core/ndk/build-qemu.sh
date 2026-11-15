#!/usr/bin/env bash
# build-qemu.sh -- cross-build qemu-system-arm (arm-softmmu) for
# aarch64-linux-android (bionic) with the Android NDK, against the glib/pixman
# prefix built by core/ndk/build-deps.sh.
#
# Environment:
#   ANDROID_NDK_ROOT  NDK root                       (default: /opt/android-ndk-${NDK_VERSION})
#   DEPS              dependency prefix             (default: /opt/mirage-deps)
#   QEMU_SRC          qemu-miwear checkout           (default: <repo>/core/qemu)
#   WORK              scratch build directory        (default: <repo>/.ndk-work)
#   NDK_API           API level                      (default: 26)
#   JOBS              parallel build jobs            (default: nproc)
#   MIRAGE_CCACHE=1   wrap compilers with ccache     (optional)
#
# Build output: $WORK/build-qemu/qemu-system-arm
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=versions.sh
. "$HERE/versions.sh"

NDK_API="${NDK_API:-26}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/opt/android-ndk-${NDK_VERSION}}}"
DEPS="${DEPS:-/opt/mirage-deps}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
REPO=$(cd "$HERE/../.." && pwd)
WORK="${WORK:-$REPO/.ndk-work}"
QEMU_SRC="${QEMU_SRC:-$REPO/core/qemu}"

export NDK ANDROID_NDK_ROOT="$NDK" DEPS WORK NDK_API MIRAGE_CCACHE="${MIRAGE_CCACHE:-}"

[ -d "$QEMU_SRC" ] || { echo "error: no QEMU source at $QEMU_SRC (run scripts/ci/fetch-qemu-src.sh)" >&2; exit 1; }
[ -f "$DEPS/lib/pkgconfig/glib-2.0.pc" ] || { echo "error: $DEPS has no glib (run core/ndk/build-deps.sh)" >&2; exit 1; }
[ -f "$DEPS/lib/pkgconfig/pixman-1.pc" ] || { echo "error: $DEPS has no pixman (run core/ndk/build-deps.sh)" >&2; exit 1; }

mkdir -p "$WORK/cross" "$WORK/logs"
"$HERE/gen-cross.sh" --api "$NDK_API" "$WORK/cross" > "$WORK/logs/gen-cross.log"
"$HERE/apply-patches.sh" "$QEMU_SRC"
SHIM="$WORK/cross/shim"
BUILD="$WORK/build-qemu"

# The shims are stable, but QEMU's configure writes an absolute pkg-config
# path into config-meson.cross; a moved $WORK forces a fresh configure.
CONFIG_STAMP="$BUILD/.cross-prefix"
if [ -d "$BUILD" ] && [ "$(cat "$CONFIG_STAMP" 2>/dev/null || true)" != "$SHIM" ]; then
    echo "== cross prefix changed, reconfiguring"
    rm -rf "$BUILD"
fi
mkdir -p "$BUILD"
printf '%s' "$SHIM" > "$CONFIG_STAMP"

# Notes on the option set:
#   * we only need system emulation of trusted Xiaomi firmware in a phone app:
#     no KVM/Xen/host USB/PCI passthrough, no networking backends, no tools;
#   * --disable-png: screendumps are consumed over VNC raw frames (the app
#     draws them itself), so the Android build does not need libpng.  The
#     host-native build (scripts/ci/build-qemu-native.sh) keeps PNG support.
#   * --disable-gio: not used by system emulation, and it drags gio-unix into
#     the bundle.
cd "$BUILD"
"$QEMU_SRC/configure" \
    --target-list=arm-softmmu \
    --cross-prefix="$SHIM/aarch64-linux-android-" \
    --disable-werror \
    --disable-docs \
    --disable-tools \
    --disable-guest-agent \
    --disable-kvm \
    --disable-xen \
    --disable-sdl \
    --disable-gtk \
    --disable-opengl \
    --disable-virglrenderer \
    --disable-slirp \
    --disable-capstone \
    --disable-curl \
    --disable-libssh \
    --disable-linux-aio \
    --disable-l2tpv3 \
    --disable-netmap \
    --disable-vde \
    --disable-af-xdp \
    --disable-libiscsi \
    --disable-libnfs \
    --disable-libcbor \
    --disable-fuse \
    --disable-fuse-lseek \
    --disable-multiprocess \
    --disable-replication \
    --disable-canokey \
    --disable-rbd \
    --disable-keyring \
    --disable-vhost-crypto \
    --disable-vhost-kernel \
    --disable-vhost-net \
    --disable-rdma \
    --disable-curses \
    --disable-vnc-jpeg \
    --disable-vnc-sasl \
    --disable-brlapi \
    --disable-dbus-display \
    --disable-seccomp \
    --disable-auth-pam \
    --disable-gio \
    --disable-png \
    --disable-attr \
    --disable-cap-ng \
    --disable-tpm \
    --disable-libusb \
    --disable-usb-redir \
    --disable-smartcard \
    --disable-u2f \
    --disable-virtfs \
    --disable-mpath \
    --disable-libudev \
    --disable-numa \
    --disable-libpmem \
    --disable-libdaxctl \
    --disable-linux-io-uring \
    --disable-modules \
    --disable-plugins \
    --disable-bpf \
    --disable-vhost-user \
    --disable-vhost-user-blk-server \
    --disable-vhost-vdpa \
    --disable-vduse-blk-export \
    --disable-vfio-user-server \
    > "$WORK/logs/qemu-configure.log" 2>&1 || {
        echo "error: qemu configure failed; tail of log:" >&2
        tail -60 "$WORK/logs/qemu-configure.log" >&2
        exit 1
    }

ninja -C "$BUILD" -j"$JOBS" > "$WORK/logs/qemu-build.log" 2>&1 || {
    echo "error: qemu build failed; last errors:" >&2
    grep -E "FAILED|error:" "$WORK/logs/qemu-build.log" | tail -40 >&2
    exit 1
}

test -x "$BUILD/qemu-system-arm"
printf '\nbuilt: %s\n' "$BUILD/qemu-system-arm"
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$BUILD/qemu-system-arm" \
    | grep -E "Class:|Machine:|Type:" | sed 's/^/    /'
