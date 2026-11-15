#!/usr/bin/env bash
# package-android.sh -- turn the NDK cross build into what an Android app can
# ship in jniLibs:
#
#   <out>/lib/libqemu-system-arm.so     the emulator (built as an executable;
#                                       renamed because AGP only packages
#                                       lib*.so from jniLibs)
#   <out>/lib/lib*.so                   every non-platform dependency
#   <out>.tar.gz                        the same tree, for the CI artifact
#
# Why the renaming: Android's dynamic linker resolves DT_NEEDED by SONAME, and
# AGP only puts files named `lib*.so` into the APK.  Each bundled library is
# therefore copied under an unversioned name, its SONAME is rewritten to that
# name, and every DT_NEEDED entry in the bundle is rewritten to match.  All
# files get DT_RUNPATH=$ORIGIN (belt) and the app is expected to set
# LD_LIBRARY_PATH=<nativeLibraryDir> as well (braces).
#
# Environment / arguments:
#   --out <dir>        output directory (required)
#   --build <dir>      QEMU build directory       (default: $WORK/build-qemu)
#   --binary <file>    QEMU executable            (default: <build>/qemu-system-arm)
#   --qemu-src <dir>   qemu-miwear checkout, for pc-bios/keymaps
#                                                 (default: <repo>/core/qemu)
#   ANDROID_NDK_ROOT   NDK root                   (default: /opt/android-ndk-${NDK_VERSION})
#   DEPS               dependency prefix          (default: /opt/mirage-deps)
#   WORK               scratch dir                (default: <repo>/.ndk-work)
#   MIRAGE_STRIP=0     keep debug symbols         (default: strip --strip-debug)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=versions.sh
. "$HERE/versions.sh"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/opt/android-ndk-${NDK_VERSION}}}"
DEPS="${DEPS:-/opt/mirage-deps}"
REPO=$(cd "$HERE/../.." && pwd)
WORK="${WORK:-$REPO/.ndk-work}"
BUILD="${BUILD:-$WORK/build-qemu}"
QEMU_SRC="${QEMU_SRC:-$REPO/core/qemu}"
BINARY=""
OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --build) BUILD="$2"; shift 2 ;;
        --binary) BINARY="$2"; shift 2 ;;
        --qemu-src) QEMU_SRC="$2"; shift 2 ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done
[ -n "$OUT" ] || { echo "error: --out is required" >&2; exit 1; }
BINARY="${BINARY:-$BUILD/qemu-system-arm}"

HOST_TAG=linux-x86_64
NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
SYSROOT_LIB="$SYSROOT/usr/lib/${NDK_TARGET}"
READELF="$NDK_BIN/llvm-readelf"
STRIP="$NDK_BIN/llvm-strip"

[ -x "$BINARY" ] || { echo "error: no emulator binary at $BINARY" >&2; exit 1; }
PATCHELF="${PATCHELF:-patchelf}"
command -v "$PATCHELF" >/dev/null 2>&1 || \
    { echo "error: patchelf is required (apt install patchelf, or set PATCHELF)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/lib"
LIBDIR="$OUT/lib"

log() { printf '  %s\n' "$*"; }
fail() { echo "error: $*" >&2; exit 1; }

# Platform libraries: present in the NDK sysroot, provided by Android at
# runtime, never bundled.  (They live under per-API dirs there, hence find.)
is_platform_lib() {
    local name="$1"
    [ -n "$(find "$SYSROOT_LIB" -maxdepth 2 -name "$name" -print -quit 2>/dev/null)" ] && return 0
    return 1
}

# libfoo.so.1 → libfoo.so (also libfoo.so.1.2.3, libfoo-bar.so.0 ...)
short_name() {
    local n="$1"
    case "$n" in
        *.so) printf '%s\n' "$n" ;;
        *.so.*)
            local base="${n%%.so.*}"
            printf '%s.so\n' "$base"
            ;;
        *) printf '%s\n' "$n" ;;
    esac
}

declare -A RENAME=()   # original SONAME/NEEDED -> bundled file name
declare -A COPIED=()   # bundled file name -> source path
QUEUE=()

needed_of() {
    "$READELF" -d "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
}

soname_of() {
    "$READELF" -d "$1" 2>/dev/null | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p'
}

enqueue() { # source path, original needed name
    local src="$1" orig="$2" short
    short=$(short_name "$orig")
    if [ -n "${COPIED[$short]:-}" ]; then
        RENAME[$orig]="$short"
        return 0
    fi
    cp -f "$src" "$LIBDIR/$short"
    COPIED[$short]="$src"
    RENAME[$orig]="$short"
    QUEUE+=("$short")
}

# --- the emulator itself ---------------------------------------------------
cp -f "$BINARY" "$LIBDIR/libqemu-system-arm.so"
ELF_FILES=("libqemu-system-arm.so")
for n in $(needed_of "$LIBDIR/libqemu-system-arm.so"); do
    if is_platform_lib "$n"; then
        log "platform: $n"
        continue
    fi
    src=""
    if [ -e "$DEPS/lib/$n" ]; then
        src="$DEPS/lib/$n"
    else
        # .pc files name the link-time name (libfoo.so), the binary records the
        # SONAME (libfoo.so.1): match the real file by prefix.
        src=$(find "$DEPS/lib" -maxdepth 1 \( -name "$n" -o -name "$n.*" \) -print -quit 2>/dev/null || true)
        if [ -z "$src" ]; then
            base=$(short_name "$n")
            src=$(find "$DEPS/lib" -maxdepth 1 -name "$base.*" -print -quit 2>/dev/null || true)
        fi
    fi
    [ -n "$src" ] || fail "cannot find '$n' (needed by libqemu-system-arm.so) in $DEPS/lib or the NDK sysroot"
    log "bundle:  $n -> $(short_name "$n")  ($src)"
    enqueue "$src" "$n"
done

# --- transitive dependencies ----------------------------------------------
i=0
while [ "$i" -lt "${#QUEUE[@]}" ]; do
    f="${QUEUE[$i]}"
    i=$((i + 1))
    for n in $(needed_of "$LIBDIR/$f"); do
        if [ -n "${RENAME[$n]:-}" ] || [ -n "${COPIED[$n]:-}" ]; then
            continue
        fi
        if is_platform_lib "$n"; then
            log "platform: $n (via $f)"
            continue
        fi
        src=$(find "$DEPS/lib" -maxdepth 1 \( -name "$n" -o -name "$n.*" \) -print -quit 2>/dev/null || true)
        if [ -z "$src" ]; then
            base=$(short_name "$n")
            src=$(find "$DEPS/lib" -maxdepth 1 -name "$base.*" -print -quit 2>/dev/null || true)
        fi
        [ -n "$src" ] || fail "cannot find '$n' (needed by $f) in $DEPS/lib or the NDK sysroot"
        log "bundle:  $n -> $(short_name "$n")  ($src)"
        enqueue "$src" "$n"
    done
done

# --- rewrite SONAMEs and DT_NEEDED ----------------------------------------
for f in "$LIBDIR"/*.so; do
    base=$(basename "$f")
    [ "$base" = "libqemu-system-arm.so" ] && continue
    "$PATCHELF" --set-soname "$base" "$f"
done

for f in "$LIBDIR"/*.so; do
    for orig in "${!RENAME[@]}"; do
        new="${RENAME[$orig]}"
        [ "$orig" = "$new" ] && continue
        if needed_of "$f" | grep -qxF "$orig"; then
            "$PATCHELF" --replace-needed "$orig" "$new" "$f"
        fi
    done
    rpath=$("$READELF" -d "$f" | sed -n 's/.*\(RUNPATH\|RPATH\).*\[\(.*\)\]/\2/p' | head -1)
    if [ "$rpath" != '$ORIGIN' ]; then
        # The bundled files are linked with -Wl,-rpath,'$ORIGIN' (see
        # core/ndk/gen-cross.sh / build-qemu.sh), so this should only run for
        # a file built outside those scripts.  patchelf grows .dynstr here,
        # which is where old patchelf versions used to corrupt PT_LOAD
        # alignment; the congruence check below catches that.
        "$PATCHELF" --set-rpath '$ORIGIN' "$f"
    fi
done

if [ "${MIRAGE_STRIP:-1}" != "0" ] && [ -x "$STRIP" ]; then
    for f in "$LIBDIR"/*.so; do
        "$STRIP" --strip-debug "$f" 2>/dev/null || true
    done
fi

# --- sanity checks ---------------------------------------------------------
ELF_FILES=()
for f in "$LIBDIR"/*.so; do ELF_FILES+=("$f"); done
for f in "${ELF_FILES[@]}"; do
    hdr=$("$READELF" -h "$f")
    echo "$hdr" | grep -q 'Machine:.*AArch64' || fail "$f is not AArch64"
done
interp=$("$READELF" -l "$LIBDIR/libqemu-system-arm.so" | \
    sed -n 's/.*interpreter: \([^]]*\).*/\1/p' | head -1)
interp=${interp%]}
[ "$interp" = "/system/bin/linker64" ] || fail "unexpected ELF interpreter: '$interp' (want /system/bin/linker64)"

# Linux execve()/mmap() require p_vaddr == p_offset (mod p_align) for every
# PT_LOAD.  patchelf < 0.19 could append a relocated .dynamic segment that
# violates this, producing a binary the kernel rejects with EINVAL; a device
# would just show a crash.  Catch it at package time instead.
for f in "$LIBDIR"/*.so; do
    python3 - "$f" <<'PYEOF' || fail "PT_LOAD alignment is invalid after patching: $f"
import struct, sys
path = sys.argv[1]
d = open(path, 'rb').read()
if d[:4] != b'\x7fELF' or d[4] != 2:
    sys.exit(0)
phoff = struct.unpack_from('<Q', d, 0x20)[0]
phentsize = struct.unpack_from('<H', d, 0x36)[0]
phnum = struct.unpack_from('<H', d, 0x38)[0]
for i in range(phnum):
    o = phoff + i * phentsize
    if struct.unpack_from('<I', d, o)[0] != 1:      # PT_LOAD
        continue
    off, vaddr = struct.unpack_from('<QQ', d, o + 8)
    align = struct.unpack_from('<Q', d, o + 48)[0]
    if align and (vaddr - off) & (align - 1):
        print(f"{path}: PT_LOAD offset={off:#x} vaddr={vaddr:#x} align={align:#x}")
        sys.exit(1)
PYEOF
done
for n in $(needed_of "$LIBDIR/libqemu-system-arm.so"); do
    is_platform_lib "$n" && continue
    short=$(short_name "$n")
    [ -e "$LIBDIR/$short" ] || fail "libqemu-system-arm.so needs $n, bundle has no $short"
done

# --- VNC keymaps -----------------------------------------------------------
# The VNC server maps client keysyms through QEMU's keymap tables; the Android
# app passes `-L <dir>` pointing at this tree (Mirage looks for
# <dir>/keymaps/en-us), which is why the app needs the keymaps next to the
# binary instead of the build host's share/qemu.
KEYMAPS="$QEMU_SRC/pc-bios/keymaps"
if [ -d "$KEYMAPS" ]; then
    mkdir -p "$OUT/share/qemu"
    cp -R "$KEYMAPS" "$OUT/share/qemu/keymaps"
    [ -f "$OUT/share/qemu/keymaps/en-us" ] || \
        echo "warning: $KEYMAPS/en-us is missing" >&2
else
    echo "warning: $KEYMAPS not found; the package will have no VNC keymaps" >&2
fi

# --- tar for the CI artifact ----------------------------------------------
parent=$(cd "$OUT/.." && pwd)
name=$(basename "$OUT")
tar -C "$parent" -czf "$OUT.tar.gz" "$name"

printf '\npackage: %s\n' "$OUT"
size=$(du -sh "$LIBDIR" | cut -f1)
printf '  %s total, files:\n' "$size"
ls -1 "$LIBDIR" | sed 's/^/    /'
printf '\n  artifact: %s\n' "$OUT.tar.gz"
