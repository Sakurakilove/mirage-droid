#!/usr/bin/env python3
"""Erase (0xFF) the /data partition of a MirageDroid SPI NAND image.

Mirage leaves /data blank for the firmware to autoformat. The profiles whose
factory data is a littlefs (q66/p67/q63) keep their bind state in
/factory.prop, so their /data must be erased even when provisioning seeded a
bound device — this script applies that erasure to an already-built image.

    python3 tools/blank_nand_data.py devices/q66/nand.img \
        --first-page 65536 --pages 196608 --page-size 2048 --oob 64

The per-profile values come from `mkdevice profile --id <id> --json`
(nand.dataStartPage/resourcePages, nand.pageSize, nand.oobSize).
"""
import argparse
import os
import sys


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("image")
    ap.add_argument("--first-page", type=int, required=True,
                    help="first NAND page of the /data partition")
    ap.add_argument("--pages", type=int, required=True,
                    help="number of pages in the /data partition")
    ap.add_argument("--page-size", type=int, default=2048)
    ap.add_argument("--oob", type=int, default=64)
    ap.add_argument("--chunk", type=int, default=1 << 20)
    args = ap.parse_args(argv)

    stride = args.page_size + args.oob
    offset = args.first_page * stride
    length = args.pages * stride
    size = os.path.getsize(args.image)
    if offset + length > size:
        print("FAIL: /data region ends past the image (%d > %d)" %
              (offset + length, size), file=sys.stderr)
        return 1
    buf = b"\xff" * min(args.chunk, length)
    with open(args.image, "r+b") as f:
        f.seek(offset)
        left = length
        while left > 0:
            n = min(left, len(buf))
            f.write(buf[:n])
            left -= n
    print("erased /data: offset %d, %d bytes (%d pages x %d B)" %
          (offset, length, args.pages, stride))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
