# Pinned versions for the Android (bionic) cross build of qemu-miwear.
#
# Sourced by every script in core/ndk/ and by the CI workflow.  Bump a pin
# here, not in the scripts: the CI caches key off this file.
#
# shellcheck shell=sh

# --- qemu-miwear (GPL-2.0) -------------------------------------------------
# The board profiles (p65/q66/...) live on dp-1700-exp.  CI clones this commit
# because the repository does not carry qemu as a git submodule.
QEMU_REPO="https://github.com/AstralSightStudios/qemu-miwear.git"
QEMU_BRANCH="dp-1700-exp"
QEMU_COMMIT="fbf301ca28dced77b71a07a250c278f236a4de5c"

# --- Android NDK -----------------------------------------------------------
# Google publishes the linux-x86_64 toolchain only; the Termux (aarch64
# bionic) host cannot run it, so the cross build happens in GitHub Actions.
NDK_VERSION="r27c"
# API 28 (Android 9) and not 26: glib requires iconv, and bionic only exposes
# <iconv.h> at API >= 28 (__INTRODUCED_IN(28)).  The app minSdk must match.
NDK_API="28"
NDK_ABI="arm64-v8a"
NDK_TARGET="aarch64-linux-android"

# --- third-party libraries ------------------------------------------------
# glib: QEMU 11 requires >= 2.66; 2.82 is what a current NDK build is tested
# with (2.84+ needs newer meson features and is not used by any Android dist).
GLIB_VERSION="2.82.4"
GLIB_URL="https://download.gnome.org/sources/glib/2.82/glib-${GLIB_VERSION}.tar.xz"
# pixman: display backend for the VNC/screendump paths we depend on.
PIXMAN_VERSION="0.44.2"
PIXMAN_URL="https://cairographics.org/releases/pixman-${PIXMAN_VERSION}.tar.gz"
# zlib: Android ships libz.so as a public NDK library, but ships no zlib.pc.
# We normally synthesise a .pc file in build-deps.sh; the source build is only
# a fallback for NDKs whose sysroot lacks <zlib.h>.
ZLIB_VERSION="1.3.1"
ZLIB_URL="https://zlib.net/fossils/zlib-${ZLIB_VERSION}.tar.gz"

# --- host tooling ----------------------------------------------------------
# meson 1.7 is new enough for QEMU 11 (>= 1.5) and glib 2.82, and is old
# enough to have no Rust/meson-version surprises.
MESON_VERSION="1.7.2"
