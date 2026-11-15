#!/usr/bin/env bash
# 真机安装 + 启动 + 截图（本机 Termux，需 KernelSU/Magisk root）
# 用法: bash scripts/dev/install_apk.sh <apk路径> [组件名] [输出png]
set -euo pipefail
APK="${1:?usage: install_apk.sh <apk> [component] [out.png]}"
ACT="${2:-com.zhaoge.miragedroid/.MainActivity}"
OUT="${3:-work/ci/screen.png}"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
su -c "cp '$APK' /data/local/tmp/m.apk && chmod 644 /data/local/tmp/m.apk && pm install -r -d /data/local/tmp/m.apk"
su -c "am start -n $ACT" >/dev/null
sleep "${WAIT:-4}"
su -c "screencap -p /data/local/tmp/screen.png"
su -c "cp /data/local/tmp/screen.png '/data/data/com.termux/files/home/mirage-droid/$OUT'"
echo "OK -> $OUT"
