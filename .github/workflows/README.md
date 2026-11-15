# GitHub Actions 工作流说明

本目录的 workflow 覆盖：APK 构建、宿主侧测试、QEMU 交叉编译、固件开机冒烟、发布。

> **本仓库所有编译都在 GitHub Actions 完成（Gradle APK / devicelib / QEMU 交叉编译 / 固件冒烟用的宿主 QEMU）。
> 本地不要求、也不建议编译**；本机只做文件检查（YAML/Python/脚本 lint）或运行 CI 产物。

## 一览

| 文件 | 触发 | 典型耗时 | 产物 | 归属 |
| --- | --- | --- | --- | --- |
| `android.yml` | `push` / `pull_request`（仅 app/core/gradle 相关路径）/ `workflow_dispatch` | ~2–3 min | Artifact `mirage-droid-debug`（debug APK）、`android-test-report` | ci-release |
| `host-tests.yml` | `push` / `pull_request`（仅相关路径）/ `workflow_dispatch` | ~3–6 min | Artifact `junit-report`、`devicelib-logs` | ci-release |
| `release.yml` | tag `v*` / `workflow_dispatch` | ~4–6 min | GitHub Release：APK、源码快照、`SHA256SUMS`、`SOURCE-OFFER.md`、`LICENSE` | ci-release |
| `qemu-android.yml` | `push`/`pull_request`（`core/ndk/**`、`scripts/ci/**`、自身） / `workflow_dispatch` | 20–40 min（ccache 命中后约 5–10 min） | Artifact `qemu-android-arm64`（`libqemu-system-arm.so` + 依赖，arm64-v8a）、失败时 `qemu-android-logs` | qemu-core |
| `firmware-smoke.yml` | **仅手动** `workflow_dispatch` | 30–60 min（含宿主 QEMU 构建） | Artifact：截图 PNG + UART/QEMU/console 日志 | ci-release |

> 约定：**任何 workflow 都不下载、不缓存、不上传小米固件**（`vela_ap.bin` / `vela_resource.bin` / OTA zip）。
> 固件只在 `firmware-smoke.yml` 手动运行时落到 runner 的 `$RUNNER_TEMP`，运行结束随 runner 销毁；
> 该 workflow 的 artifact 只含截图与文本日志。

## 各 workflow 细节

### `android.yml` — 出 APK

- 触发：`push` / `pull_request`，仅 `app/**`、`core/**`、`gradle/**`、`*.gradle.kts`、`gradle.properties`、
  `.github/workflows/android.yml` 变化时跑；保留 `workflow_dispatch`。
- 同 ref 新 push 会取消旧 run（`concurrency.cancel-in-progress: true`）。
- 关键点：runner 预装 Android SDK（`/usr/local/lib/android/sdk`），workflow 只导出
  `ANDROID_HOME`/`ANDROID_SDK_ROOT`，**不使用** `android-actions/setup-android`（该 action 在最新 runner 上会因
  `tools` 包缺失失败）。
- Gradle 由 `gradle/actions/setup-gradle@v4` 提供（`gradle-version: '8.10.2'`，自带依赖/构建缓存；
  main 可写缓存、PR 只读）。
- 步骤：`assembleDebug` → `testDebugUnitTest` → **APK 大小报告**（运行摘要里列出每个 APK 的字节/MiB，
  合计超 100 MiB 给 `::warning`，不 fail）→ 上传 APK。
- 本地复现：`gradle :app:assembleDebug`。

### `host-tests.yml` — 宿主测试与 lint

- 触发：`push` / `pull_request`，只在这些路径变化时跑：`app/**`、`core/**`、`tools/**`、`scripts/**`、
  `gradle/**`、`*.gradle.kts`、`gradle.properties`、`.github/workflows/**`；也支持手动触发。
- 三个并行 job：
  1. **workflow-lint**：`scripts/release/validate_workflows.py`（PyYAML 结构校验）+ `actionlint`（固定版本
     `1.7.12`，下载后做 SHA-256 校验；runner 自带 shellcheck，会一并检查 `run:` 脚本）。
  2. **jvm-unit**：`gradle :app:testDebugUnitTest`；JUnit XML/HTML 报告作为 artifact 上传，失败可直接定位用例。
  3. **host-devicelib**：`gcc` 构建 `tools/build_devlib.sh`（产物 `build/devlib/mkdevice`）+ 单测
     `tools/test_devlib.py`（无真机固件时自动跳过依赖固件的断言），Python 语法与关键规则 lint
     （`compileall` + `ruff --select E9,F63,F7,F82`），构建/测试日志上传。
- devicelib 脚本缺失时打印 `::warning ... SKIP` 并跳过，**不会挡住其他 workflow**；脚本合入后自动生效。
- 本地复现：
  ```bash
  python3 scripts/release/validate_workflows.py
  bash tools/build_devlib.sh && python3 tools/test_devlib.py
  gradle :app:testDebugUnitTest
  ```

### `release.yml` — 打 tag 发布

- 触发：
  - push tag `v*`（正式发布）；
  - `workflow_dispatch` 手动补发/演练，输入 `tag`（必填）、`dry_run`、`draft`、`prerelease`。
- 流程：解析 tag → 构建 APK → 签名 → `git archive` 源码快照 → `SHA256SUMS` / `BUILD-INFO.txt` →
  `RELEASE_NOTES.md` / `SOURCE-OFFER.md` → 上传 artifact →（非 dry_run 时）创建或更新 GitHub Release。
- 签名策略：
  - 四个 Secrets 齐全（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）→
    `assembleRelease` 出未签名包，再 `zipalign` + `apksigner` 签名并自检；
  - 缺任一 → 自动回退 `assembleDebug`（debug 签名），并在 release notes 与运行摘要里**明确标注仅供测试**。
- 合规产物：源码快照 `mirage-droid-<ver>-source.tar.gz`、`SOURCE-OFFER.md`（AGPL-3.0 / GPL-2.0 源码获取方式、
  qemu-miwear submodule commit、构建脚本清单）、`LICENSE`。固件绝不进 Release。
- 演练：`Actions → release → Run workflow`，`tag` 填测试 tag，勾选 `dry_run=true`（只构建不发布）。

### `firmware-smoke.yml` — 固件开机冒烟（仅手动）

- 触发：仅 `workflow_dispatch`。输入（至少一种固件来源）：
  - `firmware_url`：OTA zip 直链（zip 内 `vela_ap.bin` + `vela_resource.bin`），或 `vela_ap.bin` 直链；
  - `vela_ap_url` + `vela_resource_url`：分别给两个裸 bin 直链；
  - 其它：`profile`（p65/q66）、`boot_timeout_seconds`、`min_nonblack_ratio`。
- 为什么是 URL 而不是文件上传：GitHub `workflow_dispatch` 不支持文件输入。请提供一次性直链
  （对象存储 / 私有 release asset 临时链接）；URL 在日志中会被 `::add-mask::` 脱敏。
- 定位：**本仓库唯一的真实固件开机验证通道**（所有编译在 CI 完成，本地不编译）。
- 流程：依赖检查（缺脚本 → **直接 fail**，不 SKIP，避免“看起来绿但没验证”）→ 下载固件到 `$RUNNER_TEMP/fw` → apt 依赖
  （glib/pixman/libpng/meson/ninja）→ `bash tools/build_devlib.sh` → `bash scripts/ci/build-qemu-native.sh` →
  `mkdevice provision` 生成 `nor.img`/`nand.img` → 启动 QEMU（QMP/VNC/console socket）→
  每 5s 通过 QMP `screendump` 截图，`png-nonblack.py` 判定非全黑 → 超时/全黑时打印 UART 尾部并 fail。
- 产物（artifact，7 天）：`screen.png`、`uart0/uart2/console/qemu` 日志、`provision.log`。
  **固件与设备镜像不会上传**。
- 依赖：`tools/build_devlib.sh`（devicecore）、`scripts/ci/build-qemu-native.sh`（qemu-core，产物
  `<outdir>/qemu-system-arm`）已合入；缺失时 workflow 会 fail 并列出缺失项。

### `qemu-android.yml` — QEMU NDK 交叉编译（qemu-core 负责）

- 不在本文件维护范围（qemu-core 负责）；`ubuntu-24.04` 上用 Android NDK 交叉编译 `arm64-v8a`。
- 缓存：NDK zip、glib/pixman 交叉产物、ccache（**缓存中不含固件**）；产物 `dist/qemu-android-arm64.tar.gz`
  改名 `libqemu-system-arm.so` 放进 App `jniLibs`，失败时上传 `qemu-android-logs`。
- 详细参数以 `core/ndk/` 脚本与其 workflow 注释为准。

## Secrets

| Secret | 用途 | 必需性 |
| --- | --- | --- |
| `KEYSTORE_BASE64` | release keystore 的 base64（`base64 -w0 release.jks`） | 可选；缺省回退 debug 签名 |
| `KEYSTORE_PASSWORD` | keystore 密码 | 可选 |
| `KEY_ALIAS` | 签名 key alias | 可选 |
| `KEY_PASSWORD` | key 密码（缺省同 `KEYSTORE_PASSWORD`） | 可选 |

`GITHUB_TOKEN` 由 workflow 内置使用（`permissions: contents: write` 仅 release 需要），无需手动配置。

## 本地校验 workflow / 发布脚本

```bash
# 1) workflow 结构校验（PyYAML，无需联网）
python3 scripts/release/validate_workflows.py

# 2) actionlint（推荐；固定版本以 host-tests 为准：1.7.12）
#    Ubuntu: linux_amd64 / Termux: linux_arm64
actionlint -color

# 3) 发布/冒烟辅助脚本自测
TAG=v0.1.0 VERSION=0.1.0 SIGNED=false BUILD_TYPE=debug COMMIT=abc1234 \
  QEMU_COMMIT=unset APK_NAME=mirage-droid-0.1.0.apk APK_SIZE=3.2M APK_SHA256=deadbeef \
  SOURCE_TARBALL=mirage-droid-0.1.0-source.tar.gz \
  bash scripts/release/release-notes.sh          # 输出 release notes
python3 scripts/release/png-nonblack.py screen.png --min-ratio 0.001
python3 scripts/release/qmp-screendump.py --socket /tmp/qmp.sock --out /tmp/screen.png
```

## 约定与排查

- 不要在同一 workflow 里 `git clean -xdf` 或批量清理 `work/`：`work/refs/` 是共享的 QEMU 构建区。
- Android SDK 找不到 → 检查 `ANDROID_HOME`/`ANDROID_SDK_ROOT`；runner 预装路径是 `/usr/local/lib/android/sdk`。
- Gradle 下载慢 → `gradle/actions/setup-gradle` 已带缓存；PR 上缓存只读，main 上可写缓存。
- devicelib 相关 job 打印 `SKIP` → `tools/build_devlib.sh` 尚未合入，属预期。
- `firmware-smoke` 打印 `SKIP` → 缺 `scripts/ci/build-qemu-native.sh`（qemu-core），属预期。
- APK 签名失败 → 确认四个 Secrets 同时存在，且 keystore 可由 `base64 -d` 还原；本地可用
  `ANDROID_SDK_ROOT=... KEYSTORE_FILE=... bash scripts/release/sign-apk.sh app.apk` 复现。
