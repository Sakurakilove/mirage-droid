#!/usr/bin/env python3
"""devlib tests: profiles, factory records, littlefs, NAND, ROMFS and GPT.

Run from the repo root after building:

    bash tools/build_devlib.sh
    python3 tools/test_devlib.py

The firmware-dependent checks (real vela_ap.bin / vela_resource.bin) are
skipped when the local firmware paths are not present, so this also runs on
CI runners that only have the source tree and a synthetic OTA zip.

Environment:
    DEVLIB_P65_FW   firmware directory for REDMI Watch 6 (vela_ap.bin, vela_resource.bin)
    DEVLIB_Q66_FW   firmware directory for Xiaomi Smart Band 11
    MKDEVICE        path to the mkdevice binary (default build/devlib/mkdevice)
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MKDEVICE = os.environ.get("MKDEVICE", os.path.join(ROOT, "build", "devlib", "mkdevice"))
P65_FW = os.environ.get("DEVLIB_P65_FW", "/data/data/com.termux/files/home/lua/work/fw/out")
Q66_FW = os.environ.get("DEVLIB_Q66_FW",
                        "/data/data/com.termux/files/home/lua/work/band11/ext179")
STRIDE = 2048 + 64

failures = []
checks = [0]


def check(cond, msg):
    checks[0] += 1
    if not cond:
        failures.append(msg)
        print("FAIL: %s" % msg)
    return cond


def run(*args, expect=0):
    proc = subprocess.run([MKDEVICE] + list(args), stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE)
    out = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    if expect is not None and proc.returncode != expect:
        raise AssertionError("mkdevice %s failed (%d): %s%s" %
                             (" ".join(args), proc.returncode, out, err))
    return out, err, proc.returncode


def read_at(path, offset, size):
    with open(path, "rb") as f:
        f.seek(offset)
        return f.read(size)


def fw_available(path):
    return (os.path.isfile(os.path.join(path, "vela_ap.bin")) and
            os.path.isfile(os.path.join(path, "vela_resource.bin")))


# ---------------------------------------------------------------------------
# 1. profiles
# ---------------------------------------------------------------------------

def test_profiles():
    out, _, _ = run("profiles", "--json")
    profiles = json.loads(out)
    check(len(profiles) == 8, "expected 8 profiles, got %d" % len(profiles))
    by_id = {p["id"]: p for p in profiles}
    p65 = by_id["p65"]
    check(p65["chip"] == "best1502p" and p65["qemuBoard"] == "p65", "p65 chip/board")
    check(p65["screen"] == [432, 514], "p65 screen 432x514")
    check(p65["consoleUART"] == 1, "p65 consoleUART=1")
    check(p65["factory"]["format"] == "p65Record", "p65 factory format")
    check(p65["factory"]["offset"] == 0xCC0000, "p65 factory offset 0xCC0000")
    check(p65["nand"]["imageSize"] == 553648128, "p65 NAND image size")
    q66 = by_id["q66"]
    check(q66["chip"] == "best1503" and q66["nand"]["resourcePages"] == 65536,
          "q66 layout")
    check(q66["factory"]["format"] == "littlefs", "q66 factory littlefs")
    check(q66["consoleUART"] == 2, "q66 consoleUART=2")
    for pid in ("q65", "p67", "q63", "n66", "o66", "n67"):
        check(pid in by_id, "profile %s present" % pid)

    out, err, rc = run("profile", "--id", "nope", expect=None)
    check(rc != 0, "unknown profile is rejected")


# ---------------------------------------------------------------------------
# 2. identity (deterministic with --seed)
# ---------------------------------------------------------------------------

def test_identity(tmp):
    a, _, _ = run("identity", "--profile", "p65", "--seed", "42", "--json")
    b, _, _ = run("identity", "--profile", "p65", "--seed", "42", "--json")
    ia, ib = json.loads(a), json.loads(b)
    check(ia == ib, "identity is deterministic for a seed")
    check(ia["did"].startswith("1502") and len(ia["did"]) == 10, "p65 did prefix+6")
    check(ia["psn"].startswith("QEMU1502") and len(ia["psn"]) == 18, "p65 psn 18 chars")
    check(len(ia["authKey"]) == 32, "bind token key is 16 bytes hex")

    # explicit overrides
    c, _, _ = run("identity", "--profile", "p65", "--did", "1502000001",
                  "--psn", "QEMU1502TEST00001", "--mac", "02:00:00:00:00:01", "--json")
    ic = json.loads(c)
    check(ic["did"] == "1502000001", "identity --did override")
    check(ic["mac_bt"] == "02:00:00:00:00:01", "identity --mac override")


# ---------------------------------------------------------------------------
# 3. p65 NOR: erased flash + the 256-byte p65 record
# ---------------------------------------------------------------------------

def test_p65_nor(tmp):
    nor = os.path.join(tmp, "p65-nor.img")
    did = "1502123456"
    mac = "02:11:22:33:44:55"
    run("nor", "--profile", "p65", "--out", nor, "--did", did,
        "--psn", "QEMU1502123456789", "--mac", mac, "--sku", "M2509W1",
        "--key", "0123456789ABCDEF", "--fsn", "QEMU1502P00001")
    check(os.path.getsize(nor) == 0x1000000, "p65 NOR is 16 MiB")

    rec = read_at(nor, 0xCC0000, 256)
    check(len(rec) == 256, "p65 record readable")
    check(rec[8:14] == bytes([0x02, 0x11, 0x22, 0x33, 0x44, 0x55]),
          "p65 MAC at offset 8")
    check(rec[14:22] == struct.pack(">Q", 1502123456), "p65 DID big-endian u64 at 14")
    check(rec[22:38] == b"0123456789ABCDEF", "p65 key at 22")
    check(rec[39:56] == b"QEMU1502123456789", "p65 psn at 39")
    check(rec[63:77] == b"QEMU1502P00001", "p65 fsn at 63")
    check(rec[77:86] == b"M2509W1\0\0", "p65 sku at 77")
    check(rec[0:8] == b"\0" * 8, "p65 record starts zeroed")

    # everything outside the record is erased
    whole = read_at(nor, 0, 0x1000000)
    check(whole[:0xCC0000] == b"\xff" * 0xCC0000, "NOR erased before the record")
    check(whole[0xCC0000 + 256:] == b"\xff" * (0x1000000 - 0xCC0000 - 256),
          "NOR erased after the record")

    out, _, _ = run("factory-read", "--profile", "p65", "--image", nor)
    check("ro.factory.did=%s" % did in out, "factory-read returns the did")
    check("ro.factory.mac_bt=%s" % mac in out, "factory-read returns the MAC")
    check("ro.factory.sku=M2509W1" in out, "factory-read returns the sku")


# ---------------------------------------------------------------------------
# 4. q66 NOR: littlefs factory partition with /factory.prop
# ---------------------------------------------------------------------------

def test_q66_nor(tmp):
    nor = os.path.join(tmp, "q66-nor.img")
    run("nor", "--profile", "q66", "--out", nor, "--did", "150123456",
        "--psn", "QEMU15031234567890", "--mac", "02:AA:BB:CC:DD:EE")
    check(os.path.getsize(nor) == 0x1000000, "q66 NOR is 16 MiB")
    out, _, _ = run("factory-read", "--profile", "q66", "--image", nor)
    check("ro.factory.did=150123456" in out, "q66 littlefs holds the did")
    check("ro.factory.mac_bt=02:AA:BB:CC:DD:EE" in out, "q66 littlefs holds the MAC")
    check("persist.miwear_token_key=" in out, "q66 littlefs holds the bind token")
    check("persist.miwear_companion_device_mac=010000eeffc0" in out,
          "q66 littlefs holds the companion MAC")
    # the rest of the NOR is erased
    whole = read_at(nor, 0, 0x1000000)
    check(whole[0:32256 * 512] == b"\xff" * (32256 * 512),
          "q66 NOR erased before the factory partition")


# ---------------------------------------------------------------------------
# 5. SPI NAND image (firmware-dependent full build)
# ---------------------------------------------------------------------------

def test_nand(tmp):
    if fw_available(P65_FW):
        dev = os.path.join(tmp, "p65-dev")
        # verify-only path stays fast; the NAND image itself is built below
        out, _, _ = run("provision", "--profile", "p65", "--firmware", P65_FW,
                        "--device", dev, "--no-bind", "--no-verify")
        nand = os.path.join(dev, "nand.img")
        nor = os.path.join(dev, "nor.img")
        check(os.path.getsize(nor) == 0x1000000, "provisioned p65 NOR size")
        check(os.path.getsize(nand) == 553648128, "p65 NAND exact size")
        check(read_at(nand, 0, 8) == b"-rom1fs-", "NAND page 0 holds the resource romfs")
        # first page data equals the resource image's first page
        res = os.path.join(P65_FW, "vela_resource.bin")
        check(read_at(nand, 0, 2048) == read_at(res, 0, 2048),
              "resource page 0 copied verbatim")
        check(read_at(nand, 2048, 64) == b"\xff" * 64, "page 0 OOB erased")
        # a page well inside the resource partition
        page = 100000
        check(read_at(nand, page * STRIDE, 2048) == read_at(res, page * 2048, 2048),
              "resource page 100000 copied verbatim")
        check(read_at(nand, page * STRIDE + 2048, 64) == b"\xff" * 64,
              "resource page 100000 OOB erased")
        data_page = read_at(nand, 112640 * STRIDE, STRIDE)
        check(data_page == b"\xff" * STRIDE, "/data starts erased")
        # the identity file written by provisioning is reusable
        text = open(os.path.join(dev, "identity.properties")).read()
        check("ro.factory.psn=" in text, "identity.properties written")

        # bound provisioning seeds the yaffs2 bind databases into /data
        bound = os.path.join(tmp, "p65-bound")
        run("provision", "--profile", "p65", "--firmware", P65_FW,
            "--device", bound, "--no-verify")
        bnand = os.path.join(bound, "nand.img")
        first = read_at(bnand, 112640 * STRIDE, 2048)
        check(first != b"\xff" * 2048, "bound /data starts with a yaffs header")
        check(b"misc" in first[10:30], "bound /data first object is misc/")
        # the seeded persist.db carries this device's bind token
        ident = dict(line.strip().split("=", 1) for line in
                     open(os.path.join(bound, "identity.properties"))
                     if "=" in line)
        token = ident["persist.miwear_token_key"].encode()
        seeded = False
        for page in range(112640, 112640 + 16):
            if token in read_at(bnand, page * STRIDE, 2048):
                seeded = True
                break
        check(seeded, "bound /data persist.db carries the bind token")
        out, _, _ = run("verify", "--profile", "p65", "--device", dev)
        check("OK" in out, "verify reports OK")

        # factory-read through the provisioned NOR
        out, _, _ = run("factory-read", "--profile", "p65", "--image", nor)
        check("ro.factory.did=" in out, "provisioned NOR holds the p65 record")
    else:
        print("skip: P65 firmware not present at %s" % P65_FW)


# ---------------------------------------------------------------------------
# 6. ROMFS reader (firmware-dependent)
# ---------------------------------------------------------------------------

def test_romfs():
    if not os.path.isfile(os.path.join(P65_FW, "vela_resource.bin")):
        print("skip: P65 resource not present")
        return
    res = os.path.join(P65_FW, "vela_resource.bin")
    check(run("romfs-list", "--image", res)[0] != "", "romfs root is not empty")
    out, _, _ = run("romfs-list", "--image", res)
    names = [line.split()[-1] for line in out.splitlines() if line.strip()]
    check("watchface" in names, "romfs root has watchface")
    check(run("romfs-list", "--image", res, "--path", "/font")[0] != "",
          "romfs /font is not empty")


# ---------------------------------------------------------------------------
# 7. OTA zip import (synthetic package, always runs)
# ---------------------------------------------------------------------------

def test_ota_zip(tmp):
    pkg = os.path.join(tmp, "miwear.watch.p65_v9.9.9_full_deadbeef.bin")
    ap = b"# boot info\nCHIP=best1502p\nFLASH_SIZE=0x1000000\nOTA_CODE_OFFSET=0xC0000\n" \
         b"ro.build.version=9.9.9\nmiwear.watch.p65\n"
    res = b"-rom1fs-" + b"\0" * 504
    with zipfile.ZipFile(pkg, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("vela_ap.bin", ap)
        z.writestr("vela_resource.bin", res)
        z.writestr("ota.sh", "echo update\n")
        z.writestr("ota.json", '{"sections":[{"section_type":"main_res",'
                               '"location_path":"main_res.bin"}]}')
        z.writestr("main_res.bin", b"MR")
        z.writestr("unrelated.txt", b"drop me")
    out, _, _ = run("ota-import", "--zip", pkg, "--out", os.path.join(tmp, "fw"))
    check("miwear.watch.p65" in out and "9.9.9" in out, "OTA zip import parsed the name")
    fw = os.path.join(tmp, "fw")
    for name in ("vela_ap.bin", "vela_resource.bin", "ota.sh", "ota.json", "main_res.bin"):
        check(os.path.isfile(os.path.join(fw, name)), "imported %s" % name)
    check(not os.path.isfile(os.path.join(fw, "unrelated.txt")),
          "OTA import drops unrelated files")
    info, _, _ = run("ap-info", "--ap", os.path.join(fw, "vela_ap.bin"), "--json")
    parsed = json.loads(info)
    check(parsed["chip"] == "best1502p" and parsed["flashSize"] == 0x1000000,
          "ap-info parses CHIP/FLASH_SIZE")
    check(parsed["product"] == "miwear.watch.p65", "ap-info product")

    # a bare directory with the two images works without any zip
    dev = os.path.join(tmp, "zipdev")
    run("provision", "--profile", "p65", "--firmware", fw, "--device", dev,
        "--no-bind", "--no-verify")
    check(os.path.getsize(os.path.join(dev, "nor.img")) == 0x1000000,
          "provision from an unpacked directory")


# ---------------------------------------------------------------------------
# 8. eMMC GPT (synthetic romfs partitions)
# ---------------------------------------------------------------------------

def test_emmc(tmp):
    fw = os.path.join(tmp, "q63fw")
    os.makedirs(fw, exist_ok=True)
    for name in ("vendor", "system", "app", "health", "misc", "i18n", "font",
                 "watchface", "quickapp", "cp_image", "audio_image", "sensor", "store"):
        with open(os.path.join(fw, "vela_%s.bin" % name), "wb") as f:
            f.write(b"romfs-" + name.encode())
    img = os.path.join(tmp, "q63-emmc.img")
    run("emmc", "--profile", "q63", "--out", img, "--fw-dir", fw)
    check(os.path.getsize(img) == 4 << 30, "q63 eMMC is 4 GiB")
    check(read_at(img, 510, 2) == b"\x55\xaa", "eMMC protective MBR signature")
    check(read_at(img, 512, 8) == b"EFI PART", "eMMC GPT header at LBA 1")
    hdr = read_at(img, 512, 92)
    check(struct.unpack_from("<I", hdr, 80)[0] == 128, "GPT entry size 128")
    out, _, _ = run("emmc-list", "--image", img)
    names = [line.split()[0] for line in out.splitlines() if line.strip()]
    check(len(names) == 16, "eMMC has 13 romfs + 3 blank partitions (got %d)" % len(names))
    check("vendor" in names and "data" in names and "userlog" in names,
          "eMMC partition names")
    # primary and backup GPT tables match
    check(read_at(img, 1024, 128 * 16) == read_at(img, (4 << 30) - 33 * 512, 128 * 16),
          "eMMC primary/backup GPT entries match")
    # romfs data landed at the partition offset (1 MiB aligned)
    check(read_at(img, 1 << 20, 12) == b"romfs-vendor", "eMMC vendor partition filled")


# ---------------------------------------------------------------------------
# 9. verification failure paths
# ---------------------------------------------------------------------------

def test_failures(tmp):
    dev = os.path.join(tmp, "emptydev")
    os.makedirs(dev, exist_ok=True)
    out, err, rc = run("verify", "--profile", "p65", "--device", dev, expect=None)
    check(rc != 0, "verify fails on an empty device directory")
    out, err, rc = run("factory-read", "--profile", "p65",
                       "--image", os.path.join(tmp, "missing.img"), expect=None)
    check(rc != 0, "factory-read fails on a missing image")


def main():
    if not os.path.isfile(MKDEVICE):
        print("mkdevice not found at %s; running tools/build_devlib.sh" % MKDEVICE)
        subprocess.check_call(["bash", os.path.join(ROOT, "tools", "build_devlib.sh")])
    if not os.path.isfile(MKDEVICE):
        print("FATAL: no mkdevice binary")
        return 2
    tmp = tempfile.mkdtemp(prefix="devlib-test-")
    try:
        test_profiles()
        test_identity(tmp)
        test_p65_nor(tmp)
        test_q66_nor(tmp)
        test_nand(tmp)
        test_romfs()
        test_ota_zip(tmp)
        test_emmc(tmp)
        test_failures(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("%d checks, %d failure(s)" % (checks[0], len(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
