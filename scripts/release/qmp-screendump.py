#!/usr/bin/env python3
"""通过 QMP 让 QEMU 执行 screendump，把屏幕存成 PNG（仅标准库）。

用法：
    python3 scripts/release/qmp-screendump.py \
        --socket /abs/path/qmp.sock --out /abs/path/screen.png [--timeout 30]

背景：`firmware-smoke.yml` 用 `-qmp unix:<sock>,server=on,wait=off` 启动 QEMU，
本脚本完成 QMP 握手（qmp_capabilities）→ screendump(format=png) → 可选 quit。

退出码：0 成功；1 连接/协议错误；2 超时（socket 未就绪）。
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time


class QMPError(RuntimeError):
    pass


def connect(path: str, timeout: float) -> socket.socket:
    deadline = time.monotonic() + timeout
    last_error: OSError | None = None
    while time.monotonic() < deadline:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(max(1.0, deadline - time.monotonic()))
            sock.connect(path)
            return sock
        except OSError as exc:  # socket 还没建好：重试
            last_error = exc
            sock.close()
            time.sleep(0.5)
    raise TimeoutError(f"连接 QMP socket 超时（{timeout:.0f}s）: {path}: {last_error}")


def read_message(stream, deadline: float) -> dict:
    line = stream.readline()
    if not line:
        raise QMPError("QMP 连接被关闭（未收到消息）")
    if time.monotonic() > deadline:
        raise TimeoutError("等待 QMP 响应超时")
    try:
        message = json.loads(line)
    except json.JSONDecodeError as exc:
        raise QMPError(f"QMP 响应不是 JSON: {line!r}") from exc
    if not isinstance(message, dict):
        raise QMPError(f"QMP 响应不是 object: {message!r}")
    return message


def read_until_return(stream, deadline: float, what: str) -> dict:
    while True:
        message = read_message(stream, deadline)
        if "error" in message:
            raise QMPError(f"{what} 失败: {message['error']}")
        if "return" in message:
            return message


def run(socket_path: str, out_path: str, timeout: float, quit_after: bool) -> None:
    out_path = os.path.abspath(out_path)
    out_dir = os.path.dirname(out_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    if os.path.exists(out_path):
        os.unlink(out_path)

    sock = connect(socket_path, timeout)
    deadline = time.monotonic() + max(timeout, 10.0)
    try:
        with sock:
            stream = sock.makefile("rwb")
            greeting = read_message(stream, deadline)
            if "QMP" not in greeting:
                raise QMPError(f"不是 QMP greeting: {greeting!r}")
            print(f"QMP version={greeting['QMP'].get('version', {})}")

            stream.write(json.dumps({"execute": "qmp_capabilities"}).encode() + b"\n")
            stream.flush()
            read_until_return(stream, deadline, "qmp_capabilities")

            request = {
                "execute": "screendump",
                "arguments": {"filename": out_path, "format": "png"},
            }
            stream.write(json.dumps(request).encode() + b"\n")
            stream.flush()
            read_until_return(stream, deadline, "screendump")

            # screendump 写入在命令返回前完成；给文件系统一点余量并校验
            wait_deadline = time.monotonic() + 10.0
            while time.monotonic() < wait_deadline:
                if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
                    break
                time.sleep(0.1)
            else:
                raise QMPError(f"screendump 返回成功但未生成文件: {out_path}")

            if quit_after:
                try:
                    stream.write(json.dumps({"execute": "quit"}).encode() + b"\n")
                    stream.flush()
                except OSError:
                    pass
    finally:
        sock.close()

    print(f"screendump OK: {out_path} ({os.path.getsize(out_path)} bytes)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="QMP screendump → PNG")
    parser.add_argument("--socket", required=True, help="QMP unix socket 路径")
    parser.add_argument("--out", required=True, help="输出 PNG 路径（绝对路径更稳）")
    parser.add_argument("--timeout", type=float, default=60.0, help="连接/响应超时秒数")
    parser.add_argument("--quit", action="store_true", help="截图后发送 quit 关闭 QEMU")
    args = parser.parse_args(argv)

    try:
        run(args.socket, args.out, args.timeout, args.quit)
    except TimeoutError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except (QMPError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
