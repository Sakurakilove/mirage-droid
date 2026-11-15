#!/usr/bin/env bash
# 用 Android SDK build-tools 的 zipalign + apksigner 给 APK 签名。
#
# 用法：
#   sign-apk.sh <apk 路径> [输出路径]
#
# 环境变量（CI 由 GitHub Secrets 注入）：
#   KEYSTORE_FILE       keystore 文件路径（必填）
#   KEYSTORE_PASSWORD   keystore 密码（必填）
#   KEY_ALIAS           key alias（必填）
#   KEY_PASSWORD        key 密码（缺省时等于 KEYSTORE_PASSWORD）
#   ANDROID_SDK_ROOT / ANDROID_HOME   用于定位 build-tools
#
# 说明：不修改 app/build.gradle.kts 的 signingConfigs，因此 release 流程对
# Gradle 工程零侵入；先 assembleRelease 出 app-release-unsigned.apk，再由本脚本
# 对齐 + 签名 + 自检。
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "用法: $0 <apk> [out.apk]" >&2
  exit 2
fi

apk="$1"
out="${2:-$1}"

: "${KEYSTORE_FILE:?缺少 KEYSTORE_FILE}"
: "${KEYSTORE_PASSWORD:?缺少 KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?缺少 KEY_ALIAS}"
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

[ -f "$apk" ] || { echo "::error::APK 不存在: $apk" >&2; exit 1; }
[ -f "$KEYSTORE_FILE" ] || { echo "::error::keystore 不存在: $KEYSTORE_FILE" >&2; exit 1; }

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$sdk" ]; then
  for candidate in /usr/local/lib/android/sdk "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$candidate/build-tools" ]; then
      sdk="$candidate"
      break
    fi
  done
fi
[ -n "$sdk" ] || { echo "::error::未找到 Android SDK（ANDROID_SDK_ROOT/ANDROID_HOME 未设置）" >&2; exit 1; }

build_tools_root="$sdk/build-tools"
[ -d "$build_tools_root" ] || { echo "::error::缺少 build-tools 目录: $build_tools_root" >&2; exit 1; }
# 取版本号最高的 build-tools（sort -V 处理 35.0.0 < 35.0.1 之类）
build_tools="$(find "$build_tools_root" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
[ -n "$build_tools" ] || { echo "::error::$build_tools_root 下没有 build-tools 版本" >&2; exit 1; }

zipalign="$build_tools/zipalign"
apksigner="$build_tools/apksigner"
[ -x "$zipalign" ] || { echo "::error::zipalign 不存在: $zipalign" >&2; exit 1; }
[ -f "$apksigner" ] || { echo "::error::apksigner 不存在: $apksigner" >&2; exit 1; }

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

echo "==> zipalign: $apk"
"$zipalign" -f -p 4 "$apk" "$workdir/aligned.apk"

echo "==> apksigner sign (alias=$KEY_ALIAS, build-tools=$(basename "$build_tools"))"
# 密码通过 env: 前缀传入，不落命令行/日志
"$apksigner" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD \
  --key-pass env:KEY_PASSWORD \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$out" \
  "$workdir/aligned.apk"

echo "==> apksigner verify"
"$apksigner" verify --verbose --print-certs "$out"

echo "==> 签名完成: $out"
