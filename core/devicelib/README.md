# devicelib — MirageDroid device core

Pure C (no Android dependencies) library that turns an official firmware
package into the storage images a device boots from, plus the `mkdevice`
CLI that wraps it. It is a faithful port of the relevant parts of
[AstralSightStudios/Mirage](https://github.com/AstralSightStudios/Mirage)
(AGPL-3.0), so the images are the ones Mirage would provision for the same
device.

```
core/devicelib/
├── include/devlib.h          public API
├── src/
│   ├── devlib_profile.c      the 8 built-in device profiles (p65, q66, q65, q67…)
│   ├── devlib_identity.c     factory identity + bind state
│   ├── devlib_zip.c          OTA .zip reading (vendored miniz)
│   ├── devlib_ota.c          OTA import + vela_ap.bin boot info
│   ├── devlib_factory.c      littlefs /factory.prop, rawInfo, p65Record
│   ├── devlib_romfs.c        resource romfs reader
│   ├── devlib_yaffs2.c       yaffs2 /system + bind databases
│   ├── devlib_nand.c         page-major SPI NAND images
│   ├── devlib_emmc.c         GPT eMMC images
│   ├── devlib_device.c       one-shot provisioning + verification
│   ├── devlib_bind_resources.c  bind templates compiled in
│   └── mkdevice.c            the CLI
├── third_party/miniz/        MIT, ZIP inflate
└── resources/BindTemplate/   template databases (Mirage assets, AGPL-3.0)
```

`core/littlefs/` holds littlefs (BSD-3-Clause) and `devlib_lfs.c`, the
in-memory format/read helpers.

## Build

Host (Termux clang or Ubuntu gcc), no Android SDK needed:

```sh
bash tools/build_devlib.sh          # -> build/devlib/mkdevice
build/devlib/mkdevice self-test
python3 tools/test_devlib.py        # 80 checks
```

Or with CMake: `cmake -S core/devicelib -B build/devlib-cmake && cmake --build build/devlib-cmake`.
The Android JNI library (`libmiragedroid.so`) is built from
`app/src/main/cpp/CMakeLists.txt` via Gradle `externalNativeBuild`.

## Using it

```sh
# 1. import an official OTA package (zip) or an already unpacked directory
build/devlib/mkdevice ota-import --zip miwear.watch.p65_v3.100.043_full_662aa7db.bin --out fw/p65

# 2. provision every image the profile needs
build/devlib/mkdevice provision --profile p65 --firmware fw/p65 --device devices/p65

#    devices/p65/{nor.img,nand.img,identity.properties}
#    (add --no-bind for a never-paired device whose /data the firmware formats)

# 3. verify
build/devlib/mkdevice verify --profile p65 --device devices/p65
```

The identity is generated once and persisted in
`devices/<id>/identity.properties`; edit it (or pass `--did/--psn/--mac/...`)
to pin a device ID, serial, Bluetooth address, SKU, p65 `key`/`fsn`.

QEMU (qemu-miwear board profiles):

```sh
qemu-system-arm \
  -M miwear,profile=p65,flash-image=devices/p65/nor.img,nand-image=devices/p65/nand.img \
  -kernel fw/p65/vela_ap.bin ...
```

The NOR image is erased (0xFF) with only the factory data written: QEMU
installs the `-kernel` AP image into the boot NOR itself on every boot.

## Storage layouts (p65 example)

| image | content |
|---|---|
| `nor.img` | 16 MiB (AP `FLASH_SIZE`), all 0xFF, 256-byte p65 record at `0xCC0000` (`MAC@8`, `DID` big-endian u64 `@14`, `key@22`, `PSN@39`, `FSN@63`, `SKU@77`) |
| `nand.img` | `(2048+64) × 64 × 4096 = 553,648,128 B`; resource romfs at page 0..112640; `/data` at page 112640..262144 |

## Licensing / provenance

- This library: AGPL-3.0 (this repository).
- Ported logic and the `resources/BindTemplate/{persist,miwear_record,bt_*}.db`
  databases come from **AstralSightStudios/Mirage, AGPL-3.0**
  (`Packages/MirageKit/Sources/MirageKit/…`). They are used to make the
  firmware start already bound to a companion, exactly as Mirage does; the
  bind keys are replaced with this device's generated keys before writing.
  The same databases are embedded as C arrays
  (`src/devlib_bind_resources.c`) so Android needs no asset files.
- littlefs: BSD-3-Clause (`core/littlefs/LICENSE.md`).
- miniz: MIT (`third_party/miniz/LICENSE`).
- Firmware itself is never committed and never redistributed by this
  project; users import their own OTA package.
