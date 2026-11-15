#!/usr/bin/env bash
# verify-android-artifact.sh -- structural acceptance checks for the NDK-built
# emulator package produced by core/ndk/package-android.sh:
#
#   bash scripts/ci/verify-android-artifact.sh <dir>
#
# where <dir> is the package directory (e.g. dist/qemu-android-arm64) that
# contains lib/libqemu-system-arm.so and its bundled dependencies.
#
# This runs on the x86_64 CI runner (Android binaries cannot execute there),
# so it checks everything that is checkable statically: AArch64 ELF, bionic
# interpreter, no glibc dependencies, complete NEEDED closure, $ORIGIN rpath,
# and the presence of the board-profile table the machine prints for
# `-M miwear,profile=help`.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=../core/ndk/versions.sh
. "$HERE/../core/ndk/versions.sh"

DIR="${1:?usage: verify-android-artifact.sh <package-dir>}"
LIB="$DIR/lib"
MAIN="$LIB/libqemu-system-arm.so"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/opt/android-ndk-${NDK_VERSION}}}"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
SYSROOT_LIB="$SYSROOT/usr/lib/${NDK_TARGET}:$SYSROOT/usr/lib"

fails=0
check() { # description, command...
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        printf '  ok   %s\n' "$desc"
    else
        printf '  FAIL %s\n' "$desc"
        fails=$((fails + 1))
    fi
}

[ -x "$READELF" ] || { echo "error: llvm-readelf not found (bad NDK?)" >&2; exit 1; }
[ -f "$MAIN" ] || { echo "error: $MAIN is missing" >&2; exit 1; }

needed_of() {
    "$READELF" -d "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
}
rpath_of() {
    "$READELF" -d "$1" 2>/dev/null | sed -n 's/.*\(RUNPATH\|RPATH\).*\[\(.*\)\]/\2/p'
}
is_platform_lib() {
    local name="$1" d
    for d in ${SYSROOT_LIB//:/ }; do
        [ -n "$(find "$d" -maxdepth 2 -name "$name" -print -quit 2>/dev/null)" ] && return 0
    done
    return 1
}

echo "== package $DIR"
"$READELF" -h "$MAIN" | sed -n 's/^ */  /p' | grep -E "Class|Machine|Type" || true

echo "== checks"
check "main binary is ELF64 AArch64" bash -c "'$READELF' -h '$MAIN' | grep -q 'Class:.*ELF64' && '$READELF' -h '$MAIN' | grep -q 'Machine:.*AArch64'"
check "main binary interpreter is /system/bin/linker64" bash -c "'$READELF' -l '$MAIN' | grep -q 'interpreter: /system/bin/linker64'"

interp=$("$READELF" -l "$MAIN" | sed -n 's/.*interpreter: \(.*\)/\1/p' | head -1)
[ "$interp" = "/system/bin/linker64" ] && echo "       (interpreter: $interp)"

# No glibc linkage: bionic binaries must not mention libc.so.6 / ld-linux.
glibc=""
for f in "$LIB"/*.so; do
    if needed_of "$f" | grep -Eq 'libc\.so\.6|ld-linux|libpthread\.so\.0|librt\.so\.1'; then
        glibc="$glibc $(basename "$f")"
    fi
done
check "no glibc/ld-linux dependencies${glibc:+ (found:$glibc)}" test -z "$glibc"

# Every non-platform NEEDED must be in the bundle.
missing=""
for f in "$LIB"/*.so; do
    for n in $(needed_of "$f"); do
        is_platform_lib "$n" && continue
        [ -e "$LIB/$n" ] || missing="$missing $(basename "$f")->$n"
    done
done
check "NEEDED closure complete${missing:+ (missing:$missing)}" test -z "$missing"

# rpath: $ORIGIN on everything we ship.
badrpath=""
for f in "$LIB"/*.so; do
    r=$(rpath_of "$f")
    case "$r" in
        '$ORIGIN') ;;
        *) badrpath="$badrpath $(basename "$f")='$r'" ;;
    esac
done
check "DT_RUNPATH is \$ORIGIN on every file${badrpath:+ (bad:$badrpath)}" test -z "$badrpath"

# The board table Miwear's machine prints for `-M miwear,profile=help` must be
# in the binary; this is the same acceptance criterion as the Termux run.
check "contains the board-profile table (profile=help)" bash -c "grep -aq 'Board profiles' '$MAIN'"
check "contains the p65 board" bash -c "grep -aq 'p65' '$MAIN'"
check "contains the q66 board" bash -c "grep -aq 'q66' '$MAIN'"
check "contains the miwear machine name" bash -c "grep -aq 'miwear' '$MAIN'"
# VNC needs its keymap tables at runtime; the app passes -L <dir> pointing at
# the packaged share/qemu.  Mirage probes <dir>/keymaps/en-us.
check "VNC keymaps packaged (share/qemu/keymaps/en-us)" test -f "$DIR/share/qemu/keymaps/en-us"

echo "== bundled files"
ls -1 "$LIB" | sed 's/^/  /'
if [ -d "$DIR/share" ]; then
    echo "== keymaps"
    ls -1 "$DIR/share/qemu/keymaps" | wc -l | sed 's/^/  keymap files: /'
fi

printf '\n'
if [ "$fails" -ne 0 ]; then
    echo "FAILED: $fails check(s)" >&2
    exit 1
fi
echo "OK: artifact looks like a runnable bionic aarch64 qemu-system-arm"
