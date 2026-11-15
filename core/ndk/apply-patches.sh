#!/usr/bin/env bash
# apply-patches.sh -- apply core/ndk/patches/*.patch to a qemu-miwear
# checkout.  The patches carry the bionic/Android compatibility fixes QEMU
# needs on a Termux/NDK build; they are not part of the upstream fork, so CI
# (and a local cross build) have to apply them after fetching the source.
#
# Idempotent: a patch that is already applied (reverse-applies cleanly) is
# skipped, so re-running a build over an existing checkout is safe.
#
# Usage: apply-patches.sh <qemu-src-dir>
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC="${1:?usage: apply-patches.sh <qemu-src-dir>}"
[ -d "$SRC" ] || { echo "error: no source tree at $SRC" >&2; exit 1; }

shopt -s nullglob
patches=("$HERE"/patches/*.patch)
[ "${#patches[@]}" -gt 0 ] || { echo "== no patches to apply"; exit 0; }

for p in "${patches[@]}"; do
    name=$(basename "$p")
    if patch -p1 -R -s -f --dry-run -d "$SRC" < "$p" >/dev/null 2>&1; then
        echo "== already applied: $name"
        continue
    fi
    echo "== applying: $name"
    if ! patch -p1 -s -f -d "$SRC" < "$p"; then
        echo "error: failed to apply $name" >&2
        exit 1
    fi
done
