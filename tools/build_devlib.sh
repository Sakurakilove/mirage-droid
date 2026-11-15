#!/usr/bin/env bash
# Build MirageDroid's devlib (mkdevice CLI) with the host C compiler.
#
# Works on Termux (clang) and Ubuntu (gcc):
#   bash tools/build_devlib.sh
#   build/devlib/mkdevice self-test
#
# Environment:
#   CC          C compiler (default: clang, else gcc, else cc)
#   BUILD_DIR   output directory (default: <repo>/build/devlib)
#   CFLAGS      extra flags (appended after the defaults)
#
# Outputs:
#   $BUILD_DIR/mkdevice            the CLI
#   $BUILD_DIR/libmirage_devlib.a  the static library (when ar is available)
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BUILD_DIR=${BUILD_DIR:-"$ROOT/build/devlib"}
OBJ_DIR="$BUILD_DIR/obj"

if [ -z "${CC:-}" ]; then
    if command -v clang >/dev/null 2>&1; then
        CC=clang
    elif command -v gcc >/dev/null 2>&1; then
        CC=gcc
    else
        CC=cc
    fi
fi

BASE_CFLAGS="-O2 -g -std=c11 -Wall -Wextra -Wno-unused-parameter -fno-strict-aliasing"
INCLUDES="-I$ROOT/core/devicelib/include -I$ROOT/core/devicelib/src -I$ROOT/core/littlefs -I$ROOT/core/devicelib/third_party/miniz"
DEFINES="-D_GNU_SOURCE -D_DEFAULT_SOURCE -D_FILE_OFFSET_BITS=64 -DLFS_MULTIVERSION -DMINIZ_NO_TIME"
USER_CFLAGS=${CFLAGS:-}

SOURCES=(
    "$ROOT"/core/devicelib/src/*.c
    "$ROOT/core/littlefs/lfs.c"
    "$ROOT/core/littlefs/lfs_util.c"
    "$ROOT/core/littlefs/devlib_lfs.c"
    "$ROOT/core/devicelib/third_party/miniz/miniz.c"
    "$ROOT/core/devicelib/third_party/miniz/miniz_tdef.c"
    "$ROOT/core/devicelib/third_party/miniz/miniz_tinfl.c"
    "$ROOT/core/devicelib/third_party/miniz/miniz_zip.c"
)

mkdir -p "$OBJ_DIR"

objects=()
lib_objects=()
pids=()
for src in "${SOURCES[@]}"; do
    name=$(basename "$src" .c)
    obj="$OBJ_DIR/$name.o"
    objects+=("$obj")
    # mkdevice.o holds the CLI main(): keep it out of the static library
    if [ "$name" != "mkdevice" ]; then
        lib_objects+=("$obj")
    fi
done

# Compile in parallel (nproc-1, at least 1).
jobs=$( (command -v nproc >/dev/null 2>&1 && nproc) || echo 2 )
jobs=$((jobs > 8 ? 8 : jobs))

i=0
for src in "${SOURCES[@]}"; do
    obj="${objects[$i]}"
    # shellcheck disable=SC2086
    $CC $BASE_CFLAGS $DEFINES $INCLUDES $USER_CFLAGS -c "$src" -o "$obj" &
    pids+=($!)
    i=$((i + 1))
    if [ "${#pids[@]}" -ge "$jobs" ]; then
        for pid in "${pids[@]}"; do
            wait "$pid"
        done
        pids=()
    fi
done
for pid in "${pids[@]:-}"; do
    [ -n "$pid" ] && wait "$pid"
done

# mkdevice + static library
$CC $BASE_CFLAGS $DEFINES $INCLUDES $USER_CFLAGS \
    "${objects[@]}" -o "$BUILD_DIR/mkdevice" -lm

if command -v ar >/dev/null 2>&1; then
    ar rcs "$BUILD_DIR/libmirage_devlib.a" "${lib_objects[@]}"
fi

echo "built $BUILD_DIR/mkdevice ($($CC --version 2>/dev/null | head -1))"
bash -c "\"$BUILD_DIR/mkdevice\" self-test"
