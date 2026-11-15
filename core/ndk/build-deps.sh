#!/usr/bin/env bash
# build-deps.sh -- cross-build the dependencies QEMU needs on Android:
# glib (2.82.x) and pixman (0.44.x) for aarch64-linux-android (bionic), into
# the dependency prefix $DEPS.  zlib comes from the NDK sysroot; a zlib.pc is
# synthesised because the NDK ships the library and the header but no .pc file.
#
# This is the "3rdparty" half of the NDK build; CI caches the resulting prefix
# (see .github/workflows/qemu-android.yml) and re-runs this script, which
# returns immediately when the marker file for the pin set is present.
#
# Environment:
#   ANDROID_NDK_ROOT   NDK root                     (default: /opt/android-ndk-${NDK_VERSION})
#   DEPS               dependency install prefix    (default: /opt/mirage-deps)
#   WORK               scratch build directory      (default: <repo>/.ndk-work, gitignored by *build*)
#   NDK_API            API level                    (default: 26)
#   MIRAGE_CCACHE=1    wrap compilers with ccache   (optional)
#   JOBS               parallel build jobs          (default: nproc)
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

export NDK ANDROID_NDK_ROOT="$NDK" DEPS WORK NDK_API MIRAGE_CCACHE="${MIRAGE_CCACHE:-}"

NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
MARKER="$DEPS/.mirage-deps-${GLIB_VERSION}-pixman${PIXMAN_VERSION}-api${NDK_API}"

log() { printf '\n=== %s\n' "$*"; }

[ -d "$NDK_BIN" ] || { echo "error: bad NDK: $NDK" >&2; exit 1; }
: "${DEPS:?}"

if [ -f "$MARKER" ]; then
    log "dependencies already built for this pin set ($MARKER), skipping"
    exit 0
fi

mkdir -p "$DEPS/lib/pkgconfig" "$DEPS/share/pkgconfig" "$WORK/src" "$WORK/cross" "$WORK/logs"
CROSS="$WORK/cross/android-arm64.ini"
log "generating cross file + shims"
"$HERE/gen-cross.sh" --api "$NDK_API" "$WORK/cross" > "$WORK/logs/gen-cross.log"

# --- zlib from the NDK sysroot --------------------------------------------
# The NDK ships <zlib.h> and a linkable libz.so for every API level, but no
# pkg-config file.  QEMU's meson hard-requires dependency('zlib'), so point it
# at the platform copy: the runtime loader resolves libz.so from /system.
if [ -f "$SYSROOT/usr/include/zlib.h" ]; then
    log "writing zlib.pc for the NDK sysroot copy"
    cat > "$DEPS/lib/pkgconfig/zlib.pc" <<EOF
prefix=$SYSROOT/usr
includedir=\${prefix}/include
Name: zlib
Description: zlib compression library (Android platform copy from the NDK sysroot)
Version: ${ZLIB_VERSION}
Cflags: -I\${includedir}
Libs: -lz
EOF
else
    echo "error: $SYSROOT/usr/include/zlib.h is missing; this NDK has no zlib." >&2
    echo "       Android has always shipped zlib since API 9, so this usually" >&2
    echo "       means the NDK is corrupt." >&2
    exit 1
fi

download() { # url dest
    [ -f "$2" ] && return 0
    echo "downloading $1"
    curl -fL --retry 3 --connect-timeout 20 -o "$2.part" "$1"
    mv "$2.part" "$2"
}

# --- glib ------------------------------------------------------------------
GLIB_TAR="$WORK/src/glib-${GLIB_VERSION}.tar.xz"
download "$GLIB_URL" "$GLIB_TAR"
[ -d "$WORK/src/glib-${GLIB_VERSION}" ] || tar -C "$WORK/src" -xf "$GLIB_TAR"

log "configuring glib ${GLIB_VERSION}"
rm -rf "$WORK/build-glib"
meson setup "$WORK/build-glib" "$WORK/src/glib-${GLIB_VERSION}" \
    --cross-file "$CROSS" \
    --prefix "$DEPS" --libdir lib --buildtype release \
    --default-library shared --wrap-mode default \
    -Dtests=false \
    -Dinstalled_tests=false \
    -Dintrospection=disabled \
    -Ddocumentation=false \
    -Dselinux=disabled \
    -Dlibmount=disabled \
    -Dman-pages=disabled \
    -Ddtrace=disabled \
    -Dsystemtap=disabled \
    -Dsysprof=disabled \
    -Dnls=disabled \
    -Dbsymbolic_functions=false \
    -Dglib_debug=disabled \
    > "$WORK/logs/meson-glib-setup.log" 2>&1
log "building glib"
ninja -C "$WORK/build-glib" -j"$JOBS" > "$WORK/logs/glib-build.log" 2>&1
ninja -C "$WORK/build-glib" install > "$WORK/logs/glib-install.log" 2>&1

# --- pixman ----------------------------------------------------------------
PIXMAN_TAR="$WORK/src/pixman-${PIXMAN_VERSION}.tar.gz"
download "$PIXMAN_URL" "$PIXMAN_TAR"
[ -d "$WORK/src/pixman-${PIXMAN_VERSION}" ] || tar -C "$WORK/src" -xf "$PIXMAN_TAR"

log "configuring pixman ${PIXMAN_VERSION}"
rm -rf "$WORK/build-pixman"
meson setup "$WORK/build-pixman" "$WORK/src/pixman-${PIXMAN_VERSION}" \
    --cross-file "$CROSS" \
    --prefix "$DEPS" --libdir lib --buildtype release \
    --default-library shared --wrap-mode default \
    -Dtests=disabled \
    -Ddemos=disabled \
    -Dgtk=disabled \
    -Dlibpng=disabled \
    -Dopenmp=disabled \
    > "$WORK/logs/meson-pixman-setup.log" 2>&1
log "building pixman"
ninja -C "$WORK/build-pixman" -j"$JOBS" > "$WORK/logs/pixman-build.log" 2>&1
ninja -C "$WORK/build-pixman" install > "$WORK/logs/pixman-install.log" 2>&1

touch "$MARKER"
log "done: glib+pixman installed into $DEPS"
ls -1 "$DEPS/lib" | sed 's/^/    /'
