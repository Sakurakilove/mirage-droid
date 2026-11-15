#!/usr/bin/env bash
# 生成 SOURCE-OFFER.md：AGPL-3.0 / GPL-2.0 合规声明 + 源码获取方式 + 构建脚本清单。
# 输出到 stdout，由 .github/workflows/release.yml 写入 dist/SOURCE-OFFER.md。
#
# 必填环境变量：TAG VERSION COMMIT REPO
# 可选：QEMU_COMMIT REF RUN_URL
#
# 实现说明：正文用单引号 heredoc（不展开任何 shell 语法），随后用占位符替换注入变量，
# 避免 markdown 反引号被 shell 当命令替换执行。
set -euo pipefail

: "${TAG:?缺少 TAG}"
: "${VERSION:?缺少 VERSION}"
: "${COMMIT:?缺少 COMMIT}"
: "${REPO:?缺少 REPO}"
QEMU_COMMIT="${QEMU_COMMIT:-unset}"
QEMU_NOTE=""
if [ "$QEMU_COMMIT" = "unset" ]; then
  QEMU_NOTE="（仓库尚未引入 qemu-miwear submodule；版本 pin 见 core/ndk/versions.sh）"
  QEMU_CHECKOUT_BLOCK="# 仓库尚未引入 qemu-miwear submodule；版本 pin 见 core/ndk/versions.sh"
else
  QEMU_CHECKOUT_BLOCK="$(printf 'git clone https://github.com/AstralSightStudios/qemu-miwear.git\ncd qemu-miwear && git checkout %s' "$QEMU_COMMIT")"
fi
REF="${REF:-$TAG}"
RUN_URL="${RUN_URL:-—}"

template="$(mktemp)"
trap 'rm -f "$template"' EXIT

cat >"$template" <<'MD'
# 源码获取与合规声明 — MirageDroid __TAG__

本文件用于满足 **AGPL-3.0** 与 **GPL-2.0** 的源码提供义务。本 Release 附带
`mirage-droid-__VERSION__-source.tar.gz`（仓库在构建提交 __COMMIT__ 处的完整源码快照，
不含 git submodule 内容，submodule 源码按下表地址获取）。

## 1. 项目本体（AGPL-3.0）

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/__REPO__ |
| 版本 | __VERSION__（tag `__TAG__`） |
| 构建提交 | `__COMMIT__` |
| 获取方式 | `git clone https://github.com/__REPO__.git && git checkout __COMMIT__` |
| 许可 | AGPL-3.0（`LICENSE`） |

## 2. 内置模拟核心（GPL-2.0）

| 组件 | 上游 | 版本 | 许可 |
| --- | --- | --- | --- |
| qemu-miwear（QEMU 11.1.2 板级分支） | https://github.com/AstralSightStudios/qemu-miwear | submodule 提交 `__QEMU_COMMIT__`__QEMU_NOTE__ | GPL-2.0 |
| littlefs | https://github.com/littlefs-project/littlefs | 见 `core/littlefs` | BSD-3-Clause |

获取 qemu-miwear 对应源码：

```bash
__QEMU_CHECKOUT_BLOCK__
```

## 3. 构建脚本（可复现构建入口）

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/android.yml` | Gradle 构建 APK（本 Release 即由同类流程产出） |
| `.github/workflows/host-tests.yml` | 宿主侧 devicelib 构建/单测与 JVM 单测 |
| `.github/workflows/qemu-android.yml` | NDK 交叉编译 qemu-system-arm（产物放入 App jniLibs） |
| `.github/workflows/release.yml` | 打 tag 后的签名与发布流程 |
| `scripts/release/` | 签名（zipalign/apksigner）、release notes、合规文件生成脚本 |
| `core/ndk/` | glib/pixman/QEMU 的 Android NDK 交叉构建脚本 |
| `tools/build_devlib.sh` | devicelib 宿主构建（gcc/clang） |

构建命令（与 CI 一致）：

```bash
# APK
gradle :app:assembleDebug        # 或 assembleRelease
# 宿主 devicelib
bash tools/build_devlib.sh
```

## 4. 固件声明

本 Release **不包含**任何小米固件（`vela_ap.bin` / `vela_resource.bin` / OTA zip 等）。
固件版权归小米所有，用户需自行合法获取并在 App 内本地导入；CI 不缓存、不上传固件。

## 5. 构建信息

- 源码快照：`mirage-droid-__VERSION__-source.tar.gz`
- 校验：`SHA256SUMS`
- CI 运行记录：__RUN_URL__
- 构建引用：`__REF__`
MD

sed \
  -e "s|__TAG__|${TAG}|g" \
  -e "s|__VERSION__|${VERSION}|g" \
  -e "s|__COMMIT__|${COMMIT}|g" \
  -e "s|__REPO__|${REPO}|g" \
  -e "s|__QEMU_COMMIT__|${QEMU_COMMIT}|g" \
  -e "s|__REF__|${REF}|g" \
  -e "s|__RUN_URL__|${RUN_URL}|g" \
  -e "s|__QEMU_NOTE__|${QEMU_NOTE}|g" \
  -e "s|__QEMU_COMMIT__|${QEMU_COMMIT}|g" \
  "$template" |
  while IFS= read -r line; do
    if [ "$line" = "__QEMU_CHECKOUT_BLOCK__" ]; then
      printf '%s\n' "$QEMU_CHECKOUT_BLOCK"
    else
      printf '%s\n' "$line"
    fi
  done
