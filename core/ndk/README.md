# core/ndk — Android (bionic) cross build of qemu-miwear

This directory builds `qemu-system-arm` for **Android arm64-v8a** with the
Android NDK, plus the libraries QEMU needs (glib, pixman) and a copy of the
VNC keymaps. The result is what the app ships in `jniLibs`.

The heavy build runs in GitHub Actions (`.github/workflows/qemu-android.yml`):
Google ships only a linux-x86_64 NDK, so a Termux (aarch64 Android) machine
cannot run the cross toolchain itself.

**The target API level is 28 (Android 9).** That is the first Android
release whose bionic exposes `<iconv.h>`, and glib cannot be built without
iconv. The app's `minSdk` therefore has to be 28 as well.

## Why the extra steps

* **glib and pixman have no Android package.** They are cross-compiled from
  source into `$DEPS` first; QEMU's meson then finds them through a pkg-config
  wrapper that only exposes `$DEPS` (never the build host's x86_64 `.pc`
  files).
* **The NDK ships zlib but no `zlib.pc`.** `build-deps.sh` synthesises one;
  `libz.so` itself comes from Android at runtime, so it is not bundled.
* **bionic is not glibc.** `patches/` carries the small QEMU fixes:
  `SYS_gettid`/`__NR_tgkill` aliases (glibc extensions bionic lacks),
  Android host detection (`CONFIG_LINUX` unset although `__linux__` is set),
  and a file-backed `shm_open`/`shm_unlink` (bionic has no POSIX shm).
  `apply-patches.sh` applies them idempotently after the source is fetched.
* **AGP only packages `lib*.so`.** `package-android.sh` copies each dependency
  under its unversioned name, rewrites its SONAME and every `DT_NEEDED`
  accordingly, and sets `DT_RUNPATH=$ORIGIN`. The app should still set
  `LD_LIBRARY_PATH=<nativeLibraryDir>` when it spawns QEMU.
* **VNC needs keymaps.** The package carries
  `share/qemu/keymaps/`; the app passes `-L <dir>` where
  `<dir>/keymaps/en-us` exists (Mirage looks for exactly that).

## Files

| File | Purpose |
|---|---|
| `versions.sh` | All pins: qemu-miwear commit, NDK r27c/API 28, glib 2.82.4, pixman 0.44.2, meson 1.7.2. CI caches key off it. |
| `gen-cross.sh` | Writes the Meson cross file (`android-arm64.ini`) and the `--cross-prefix` shims (`shim/aarch64-linux-android-*`). |
| `apply-patches.sh` | Applies `patches/*.patch` to a checkout, idempotently. |
| `build-deps.sh` | Cross-builds glib + pixman into `$DEPS`, synthesises `zlib.pc`. Cached in CI. |
| `build-qemu.sh` | Cross-builds `qemu-system-arm` into `$WORK/build-qemu/`. |
| `package-android.sh` | Builds the jniLibs tree + tarball with `$ORIGIN` rpath, unversioned names, keymaps. |
| `build-termux.sh` | Native Termux build of the same source + patches, for on-device testing. |
| `patches/` | bionic compatibility patches (see above). |

## CI

`.github/workflows/qemu-android.yml` runs on changes under `core/ndk/`,
`scripts/ci/` or itself (and manually via *Run workflow*):

1. installs meson/ninja/patchelf/ccache and the NDK r27c (zip cached);
2. `scripts/ci/fetch-qemu-src.sh core/qemu` — clones the upstream fork and
   **asserts** HEAD == the commit pinned in `versions.sh`;
3. `core/ndk/build-deps.sh` — glib/pixman prefix cached across runs;
4. `core/ndk/build-qemu.sh` — arm-softmmu cross build;
5. `core/ndk/package-android.sh --out dist/qemu-android-arm64`;
6. `scripts/ci/verify-android-artifact.sh` — static checks (AArch64 ELF,
   `/system/bin/linker64`, no glibc deps, complete `NEEDED` closure,
   `$ORIGIN` rpath, board table and keymaps present);
7. uploads `qemu-android-arm64` (tar.gz).

Artifact layout:

```
qemu-android-arm64/
├── lib/
│   ├── libqemu-system-arm.so        # the emulator (was qemu-system-arm)
│   ├── libglib-2.0.so …             # only what QEMU actually links
│   └── …
└── share/qemu/keymaps/en-us …
```

## On-device validation (Termux, also bionic)

The runner is x86_64 and cannot execute the artifact, so the real acceptance
is done on an Android device:

```bash
tar xzf qemu-android-arm64.tar.gz && cd qemu-android-arm64
LD_LIBRARY_PATH="$PWD/lib" ./lib/libqemu-system-arm.so -M miwear,profile=help
# expected: "Board profiles (profile=<name>):" with p65/q66/...
```

Booting a real firmware (P65) locally, with images built by `core/devicelib`:

```bash
./lib/libqemu-system-arm.so \
  -M miwear,profile=p65,flash-image=$IMG/nor.img,nand-image=$IMG/nand.img \
  -kernel $FW/vela_ap.bin \
  -display none -vnc unix:$RUN/vnc.sock \
  -qmp unix:$RUN/qmp.sock,server=on,wait=off -monitor none \
  -chardev socket,id=con,path=$RUN/console.sock,server=on,wait=off \
  -serial file:$RUN/uart0.log -serial chardev:con -serial file:$RUN/uart2.log \
  -global driver=bes.btss,property=bdaddr,value=AA:BB:CC:DD:EE:FF \
  -L "$PWD/share/qemu" -D $RUN/qemu.log
```

`-serial` values are positional (`-serial 1=…` is not QEMU syntax): P65 has
`consoleUART=1, uartCount=3`, so the console goes in position 1. Firmware
images are never committed or uploaded to CI.

## Reproducing locally

```bash
# host-native (x86_64 Linux), for firmware smoke tests
sudo apt-get install -y libglib2.0-dev libpixman-1-dev libpng-dev \
    ninja-build pkg-config meson git
bash scripts/ci/build-qemu-native.sh ./qemu-native

# Termux (aarch64 bionic), for on-device testing
pkg install clang binutils glib pixman ninja python
python3 -m pip install meson
bash core/ndk/build-termux.sh
```

## Bumping versions

Change the pin in `core/ndk/versions.sh` only. The dependency cache key
changes with the file, so a glib/pixman bump rebuilds them; a QEMU commit bump
is validated by `fetch-qemu-src.sh`. If the upstream fork moves, update
`QEMU_COMMIT` in the same commit as the code that needs it.

## Licensing

The artifact is GPL-2.0 (QEMU) linked against LGPL-2.1 (glib), MIT (pixman);
the app is AGPL-3.0. Build scripts and patches are part of this repository so
the whole corresponding source is available. QEMU is cloned from
`AstralSightStudios/qemu-miwear` (a QEMU fork); see `docs/COMPLIANCE.md`.
