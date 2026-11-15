# GitHub Actions 工作流说明

本目录的 workflow 覆盖：APK 构建、宿主侧测试、QEMU 交叉编译、固件开机冒烟、发布。
所有重型编译（NDK / QEMU）都在 GitHub Actions 上完成；本地只需 Java 21（可选）即可跑纯 JVM 测试。

## 一览

| 文件 | 触发 | 典型耗时 | 产物 | 归属 |
| --- | --- | --- | --- | --- |
| `android.yml` | `push` / `pull_request` / `workflow_dispatch` | ~2m20s | Artifact `mirage-droid-debug`（debug APK） | ci-release |
| `host-tests.yml` | `push` / `pull_request`（仅相关路径）/ `workflow_dispatch` | ~3–6 min | Artifact `junit-report`、`devicelib-logs` | ci-release |
| `release.yml` | tag `v*` / `workflow_dispatch` | ~4–6 min | GitHub Release：APK、源码快照、`SHA256SUMS`、`SOURCE-OFFER.md`、`LICENSE` | ci-release |
| `qemu-android.yml` | 待合入（计划：`push` 相关路径 / `workflow_dispatch`） | 20–40 min（含 ccache 命中后 5–10 min） | `qemu-system-arm` + 依赖 `.so`（arm64-v8a） | qemu-core |
| `firmware-smoke.yml` | 仅 `workflow_dispatch`（手动） | 30–60 min | 截图 PNG / 序列日志（固件绝不入库、不缓存） | 规划中（第二批） |

> 约定：**任何 workflow 都不下载、不缓存、不上传小米固件**（`vela_ap.bin` / `vela_resource.bin` / OTA zip）。
> 固件只在 `firmware-smoke.yml` 的手动运行中使用临时目录，运行结束随 runner 销毁。

## 各 workflow 细节

### `android.yml` — 出 APK

- 触发：所有 `push` / `pull_request` + 手动。
- 关键点：runner 预装 Android SDK（`/usr/local/lib/android/sdk`），workflow 只导出
  `ANDROID_HOME`/`ANDROID_SDK_ROOT`，**不使用** `android-actions/setup-android`（该 action 在最新 runner 上会因
  `tools` 包缺失失败）。
- Gradle 由 `gradle/actions/setup-gradle@v4` 提供（`gradle-version: '8.10.2'`，自带依赖/构建缓存）。
- 步骤：`assembleDebug` → `testDebugUnitTest` → 上传 `app/build/outputs/apk/debug/*.apk`（缺失则 fail）。
- 本地复现：`gradle :app:assembleDebug`。

### `host-tests.yml` — 宿主测试与 lint

- 触发：`push` / `pull_request`，但只在这些路径变化时跑：`app/**`、`core/**`、`tools/**`、`scripts/**`、
  `gradle/**`、`*.gradle.kts`、`gradle.properties`、`.github/workflows/**`；也支持手动触发。
- 三个并行 job：
  1. **workflow-lint**：`scripts/release/validate_workflows.py`（PyYAML 结构校验）+ `actionlint`（固定版本
     `1.7.12`，带 SHA-256 校验）。
  2. **jvm-unit**：`gradle :app:testDebugUnitTest`；JUnit XML/HTML 报告作为 artifact 上传，失败可直接定位用例。
  3. **host-devicelib**：`gcc` 构建 `tools/build_devlib.sh` + 单测（`tools/test_devlib.py` / `.sh`），
     Python 语法与关键规则 lint（`compileall` + `ruff --select E9,F63,F7,F82`），构建/测试日志上传。
- devicelib 脚本尚未合入时打印 `::warning ... SKIP` 并跳过，**不会挡住其他 workflow**；脚本合入后自动生效。
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
- 演练：`Actions → release → Run workflow`，`tag` 填一个测试 tag，勾选 `dry_run=true`。

### `qemu-android.yml` — QEMU NDK 交叉编译（qemu-core 负责）

- 不在本文件维护范围；约定：ubuntu-latest + NDK 交叉编译 `arm64-v8a`，产物 `qemu-system-arm` 与依赖 `.so`
  打包为 artifact（最终改名为 `libqemu-system-arm.so` 放进 App `jniLibs`）。
- 详细参数以 `core/ndk/` 脚本与其 workflow 注释为准。

### `firmware-smoke.yml` — 固件开机冒烟（手动，规划中）

- 仅手动触发，输入固件 URL 或上传文件；ubuntu 上原生构建 QEMU + devicelib 镜像 → 启动 → QMP 截 PNG →
  判定非全黑。固件只落在 runner 临时目录，**不缓存、不上传、不进 artifact**。

## Secrets

| Secret | 用途 | 必需性 |
| --- | --- | --- |
| `KEYSTORE_BASE64` | release keystore 的 base64（`base64 -w0 release.jks`） | 可选；缺省回退 debug 签名 |
| `KEYSTORE_PASSWORD` | keystore 密码 | 可选 |
| `KEY_ALIAS` | 签名 key alias | 可选 |
| `KEY_PASSWORD` | key 密码（缺省同 `KEYSTORE_PASSWORD`） | 可选 |

`GITHUB_TOKEN` 由 workflow 内置使用（`permissions: contents: write`），无需手动配置。

## 本地校验 workflow / 发布脚本

```bash
# 结构校验（PyYAML，无需联网）
python3 scripts/release/validate_workflows.py

# actionlint（推荐；Linux/macOS 下载官方 release 资产）
# 说明：Termux 用 linux_arm64 版本，Ubuntu 用 linux_amd64
actionlint -color

# 发布脚本试跑
TAG=v0.1.0 VERSION=0.1.0 SIGNED=false BUILD_TYPE=debug COMMIT=abc1234 \
  QEMU_COMMIT=unset APK_NAME=mirage-droid-0.1.0.apk APK_SIZE=3.2M APK_SHA256=deadbeef \
  SOURCE_TARBALL=mirage-droid-0.1.0-source.tar.gz \
  bash scripts/release/release-notes.sh
```

## 约定与排查

- 不要在同一 workflow 里 `git clean -xdf` 或批量清理 `work/`：`work/refs/` 是共享的 QEMU 构建区。
- Android SDK 找不到 → 检查 `ANDROID_HOME`/`ANDROID_SDK_ROOT`；runner 预装路径是 `/usr/local/lib/android/sdk`。
- Gradle 下载慢 → `gradle/actions/setup-gradle` 已带缓存；PR 上设了 `cache-read-only`，main 上可写缓存。
- devicelib 相关 job 打印 `SKIP` → `tools/build_devlib.sh` 尚未合入，属预期。
- APK 签名失败 → 确认四个 Secrets 同时存在，且 keystore 可由 `base64 -d` 还原；本地可用
  `ANDROID_SDK_ROOT=... KEYSTORE_FILE=... bash scripts/release/sign-apk.sh app.apk` 复现。
