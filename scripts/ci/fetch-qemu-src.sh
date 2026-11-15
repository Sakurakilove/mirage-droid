#!/usr/bin/env bash
# fetch-qemu-src.sh -- clone qemu-miwear at the commit pinned in
# core/ndk/versions.sh and verify the pin.
#
# The repository does not carry QEMU as a git submodule on purpose (it would
# make every clone pay for ~400 MB of history), so every build fetches the
# upstream fork itself and asserts the exact commit.
#
# Usage: fetch-qemu-src.sh <dest-dir>
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=../../core/ndk/versions.sh
. "$HERE/../../core/ndk/versions.sh"

DEST="${1:?usage: fetch-qemu-src.sh <dest-dir>}"

if [ -d "$DEST/.git" ]; then
    echo "== qemu source already at $DEST"
else
    mkdir -p "$(dirname "$DEST")"
    echo "== cloning $QEMU_REPO ($QEMU_BRANCH) into $DEST"
    git clone --depth 1 --branch "$QEMU_BRANCH" "$QEMU_REPO" "$DEST"
fi

HEAD=$(git -C "$DEST" rev-parse HEAD)
if [ "$HEAD" != "$QEMU_COMMIT" ]; then
    echo "== branch $QEMU_BRANCH is at $HEAD, pinned commit is $QEMU_COMMIT"
    # The branch may have moved since the pin was written; fetch the pinned
    # commit explicitly when the server allows it.
    if git -C "$DEST" fetch --depth 1 origin "$QEMU_COMMIT" 2>/dev/null; then
        git -C "$DEST" checkout --detach "$QEMU_COMMIT"
        HEAD=$(git -C "$DEST" rev-parse HEAD)
    fi
fi
if [ "$HEAD" != "$QEMU_COMMIT" ]; then
    echo "error: QEMU source is at $HEAD, expected pinned $QEMU_COMMIT." >&2
    echo "       Either update QEMU_COMMIT in core/ndk/versions.sh, or remove" >&2
    echo "       $DEST and re-run this script." >&2
    exit 1
fi
echo "== qemu source pinned at $HEAD"
