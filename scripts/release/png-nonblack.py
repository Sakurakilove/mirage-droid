#!/usr/bin/env python3
"""判断 QEMU screendump 的 PNG 是否“非全黑”（仅标准库，无 Pillow 依赖）。

用于 `firmware-smoke.yml`：QEMU 启动 P65 固件后用 QMP 截屏，判定面板是否点亮。

支持：PNG 8-bit、非隔行；颜色类型 0（灰度）/2（RGB）/4（灰度+alpha）/6（RGBA）。
QEMU 的 screendump PNG 通常是 8-bit RGB。

判定：亮度 `max(r,g,b) > --threshold`（默认 24）的像素占比 >= `--min-ratio`（默认 0.001）。

用法：
    python3 scripts/release/png-nonblack.py screen.png [--min-ratio 0.001] [--threshold 24]

退出码：0 非黑通过；1 全黑/低于阈值；2 解码失败。
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw: bytes, width: int, height: int, channels: int) -> bytearray:
    stride = width * channels
    result = bytearray(height * stride)
    offset = 0
    for y in range(height):
        if offset >= len(raw):
            raise ValueError(f"IDAT 数据不足：第 {y} 行")
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        if len(line) != stride:
            raise ValueError(f"IDAT 数据不足：第 {y} 行需要 {stride} 字节，实际 {len(line)}")
        offset += stride
        previous = result[(y - 1) * stride:y * stride] if y > 0 else bytes(stride)
        if filter_type == 0:
            pass
        elif filter_type == 1:  # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up_left = previous[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, previous[i], up_left)) & 0xFF
        else:
            raise ValueError(f"未知 PNG filter: {filter_type}")
        result[y * stride:(y + 1) * stride] = line
    return result


def decode_png(path: Path) -> tuple[int, int, int, bytes]:
    """返回 (width, height, channels, 解压+反滤波后的像素数据)。"""
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("不是 PNG 文件（签名不匹配）")
    offset = len(PNG_SIGNATURE)
    width = height = bit_depth = color_type = interlace = 0
    idat = bytearray()
    while offset + 8 <= len(data):
        length, chunk_type = struct.unpack(">I4s", data[offset:offset + 8])
        offset += 8
        payload = data[offset:offset + length]
        offset += length + 4  # 跳过 CRC
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
        elif chunk_type == b"IDAT":
            idat += payload
        elif chunk_type == b"IEND":
            break
    if not width or not height:
        raise ValueError("PNG 缺少 IHDR")
    if bit_depth != 8:
        raise ValueError(f"仅支持 8-bit PNG，实际 bit_depth={bit_depth}")
    if interlace != 0:
        raise ValueError("不支持隔行 PNG")
    channels_by_type = {0: 1, 2: 3, 4: 2, 6: 4}
    if color_type not in channels_by_type:
        raise ValueError(f"不支持的颜色类型 color_type={color_type}")
    channels = channels_by_type[color_type]
    raw = zlib.decompress(bytes(idat))
    pixels = _unfilter(raw, width, height, channels)
    return width, height, channels, pixels


def analyze(path: Path, threshold: int, min_ratio: float) -> dict:
    width, height, channels, pixels = decode_png(path)
    total = width * height
    nonblack = 0
    for index in range(0, len(pixels), channels):
        if channels in (1, 2):
            value = pixels[index]
            if value > threshold:
                nonblack += 1
        else:
            if max(pixels[index], pixels[index + 1], pixels[index + 2]) > threshold:
                nonblack += 1
    ratio = nonblack / total if total else 0.0
    return {
        "width": width,
        "height": height,
        "channels": channels,
        "total_pixels": total,
        "nonblack_pixels": nonblack,
        "nonblack_ratio": ratio,
        "threshold": threshold,
        "min_ratio": min_ratio,
        "ok": ratio >= min_ratio,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PNG 非全黑判定（QEMU screendump 冒烟）")
    parser.add_argument("png", type=Path, help="PNG 路径")
    parser.add_argument("--min-ratio", type=float, default=0.001, help="非黑像素最低占比（默认 0.001）")
    parser.add_argument("--threshold", type=int, default=24, help="单通道亮度阈值（默认 24）")
    args = parser.parse_args(argv)

    if not args.png.is_file():
        print(f"ERROR: 文件不存在: {args.png}", file=sys.stderr)
        return 2
    try:
        result = analyze(args.png, args.threshold, args.min_ratio)
    except (ValueError, zlib.error, struct.error) as exc:
        print(f"ERROR: PNG 解码失败: {exc}", file=sys.stderr)
        return 2

    print(
        "PNG {width}x{height} channels={channels} | 非黑像素 {nonblack_pixels}/{total_pixels} "
        "= {ratio:.4%}（阈值 >{threshold}，要求 >= {min:.4%}）".format(
            ratio=result["nonblack_ratio"],
            min=result["min_ratio"],
            **result,
        )
    )
    if not result["ok"]:
        print(
            f"FAIL: 屏幕几乎全黑（{result['nonblack_ratio']:.4%} < {result['min_ratio']:.4%}），"
            "固件可能未启动到 UI",
            file=sys.stderr,
        )
        return 1
    print("OK: 屏幕非全黑（固件已点亮面板）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
