#!/usr/bin/env bash
# 生成 GitHub Release 的 release notes（markdown，输出到 stdout）。
#
# 由 .github/workflows/release.yml 调用；也可以本地试跑：
#   TAG=v0.1.0 VERSION=0.1.0 SIGNED=false BUILD_TYPE=debug COMMIT=abc1234 \
#   QEMU_COMMIT=unset APK_NAME=mirage-droid-0.1.0.apk APK_SIZE=3.2M \
#   APK_SHA256=deadbeef SOURCE_TARBALL=mirage-droid-0.1.0-source.tar.gz \
#   REPO=Sakurakilove/mirage-droid RUN_URL=https://example/run \
#   scripts/release/release-notes.sh
#
# 必填环境变量：TAG VERSION SIGNED BUILD_TYPE COMMIT APK_NAME APK_SIZE APK_SHA256
# 可选：QEMU_COMMIT SOURCE_TARBALL REPO RUN_URL
set -euo pipefail

: "${TAG:?缺少 TAG}"
: "${VERSION:?缺少 VERSION}"
: "${SIGNED:?缺少 SIGNED (true/false)}"
: "${BUILD_TYPE:?缺少 BUILD_TYPE (release/debug)}"
: "${COMMIT:?缺少 COMMIT}"
: "${APK_NAME:?缺少 APK_NAME}"
: "${APK_SIZE:?缺少 APK_SIZE}"
: "${APK_SHA256:?缺少 APK_SHA256}"
QEMU_COMMIT="${QEMU_COMMIT:-unset}"
SOURCE_TARBALL="${SOURCE_TARBALL:-}"
REPO="${REPO:-Sakurakilove/mirage-droid}"
RUN_URL="${RUN_URL:-}"

signed_note="**debug 签名（仅供测试，不可用于正式分发）**"
if [ "$SIGNED" = "true" ]; then
  signed_note='正式发布签名（`KEYSTORE_*` Secrets 已配置）'
fi

cat <<EOF
# MirageDroid ${TAG}

> 安卓手机端原生小米穿戴模拟器。优先支持 **Redmi Watch 6（P65）** 与 **小米手环 11（q66cn）**。
> 模拟核心移植自 [qemu-miwear](https://github.com/AstralSightStudios/qemu-miwear)（QEMU 11.1.2，GPL-2.0）。

## 本次构建

| 项 | 值 |
| --- | --- |
| 版本 | \`${VERSION}\` |
| 构建类型 | \`${BUILD_TYPE}\` |
| 签名 | ${signed_note} |
| 提交 | \`${COMMIT}\` |
| qemu-miwear submodule | \`${QEMU_COMMIT}\` |
| 触发方式 | tag push / workflow_dispatch |
| CI 运行 | ${RUN_URL:-—} |

## 产物

| 文件 | 说明 |
| --- | --- |
| \`${APK_NAME}\` | 安装包（${APK_SIZE}，sha256 \`${APK_SHA256}\`） |
$(if [ -n "$SOURCE_TARBALL" ]; then echo "| \`${SOURCE_TARBALL}\` | 完整源码快照（AGPL-3.0 合规） |"; fi)
| \`SHA256SUMS\` | 所有产物的 SHA-256 |
| \`SOURCE-OFFER.md\` | 源码获取方式、第三方组件与构建脚本清单 |
| \`LICENSE\` | AGPL-3.0 全文 |

## 安装

\`\`\`bash
adb install -r ${APK_NAME}
\`\`\`

$(if [ "$SIGNED" != "true" ]; then cat <<'EON'
> ⚠️ **这是 debug 签名的构建产物**：仓库未配置发布签名 Secrets
> （`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
> 因此 CI 自动回退到 debug 签名。它只能用于测试：debug key 每台机器不同，
> 后续正式签名版本无法覆盖安装（需先卸载）。配置上述 Secrets 后重新打 tag 即可产出正式签名包。
EON
fi)

## 许可与源码

- 本项目：**AGPL-3.0**（见 \`LICENSE\`）。本 Release 附完整源码快照，对应构建提交 \`${COMMIT}\`。
- 模拟核心 [qemu-miwear](https://github.com/AstralSightStudios/qemu-miwear)：**GPL-2.0**，submodule 提交 \`${QEMU_COMMIT}\`；
  其源码与 \`core/ndk/\` 构建脚本随源码快照一并提供。
- [littlefs](https://github.com/littlefs-project/littlefs)：BSD-3-Clause。
- 源码仓库：https://github.com/${REPO}/tree/${TAG}

## 不包含固件

本 Release **不包含任何小米固件**（\`vela_ap.bin\` / \`vela_resource.bin\` 等）。固件版权归小米所有，
用户需自行合法获取 OTA 包，并在 App 内本地导入。CI 不缓存、不上传固件。

## 已知限制

- 当前处于开发阶段，功能与界面会变化；设备兼容以仓库 README / \`docs/ARCHITECTURE.md\` 为准。
- 模拟核心（QEMU 交叉编译产物）是否内嵌取决于构建时的 \`qemu-android\` 产物状态。
EOF
