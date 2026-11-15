# 构建与发布（BUILD）

本文覆盖 MirageDroid 的 **CI 全流程**与可选的本地检查：产物在哪、怎么复现、失败怎么查。
发布侧的 Secrets/Release 约定见 [`.github/workflows/README.md`](../.github/workflows/README.md)。

> **本仓库所有编译（Gradle APK、devicelib、QEMU 交叉编译、固件冒烟用的宿主 QEMU）全部在 GitHub Actions 完成。**
> 本地不要求、也不建议编译；本机只做文件检查（YAML/Python/脚本 lint）或直接运行 CI 产物。
> 下文本地命令均标注为「可选，CI 已覆盖」，仅用于排障或离线验证。

## 0. 前提

| 用途 | 需要 | 说明 |
| --- | --- | --- |
| Android APK | JDK 21 + Android SDK（platform 35 / build-tools） | 全部由 CI 完成；本地构建仅排障 |
| 纯 JVM 单测 | JDK 21 + Android SDK | AGP 配置阶段需要 SDK 路径 |
| devicelib 宿主构建 | `gcc`/`clang`、`cmake`（可选）、`python3` | Linux / Termux 均可 |
| QEMU 宿主构建 | `meson`、`ninja`、`glib`、`pixman`、`pkg-config` | 见 `scripts/ci/`（qemu-core 提供） |
| 发布签名 | `KEYSTORE_BASE64` 等 4 个 Secrets | 缺失时 CI 回退 debug 签名 |

本地 Gradle：本仓库尚未提交 `gradle/wrapper`，CI 用 `gradle/actions/setup-gradle@v4` 固定
`gradle-version: '8.10.2'`。本地请用 Gradle **8.10.2**（`gradle -v` 确认），不要混用 other 版本。

## 1. 本地检查 / 构建（可选，CI 已覆盖）

### 1.1 纯 JVM 单测（可选，CI 已覆盖）

```bash
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"   # 按本机路径改
gradle :app:testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

### 1.2 debug APK（可选，CI 已覆盖）

```bash
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

设备安装（root 真机，脚本 `scripts/dev/install_apk.sh`）：

```bash
bash scripts/dev/install_apk.sh app/build/outputs/apk/debug/app-debug.apk
```

### 1.3 devicelib（宿主侧，可选，CI 已覆盖）

```bash
bash tools/build_devlib.sh      # gcc/clang 编 mkdevice 与单测目标；产物 build/devlib/mkdevice
python3 tools/test_devlib.py    # 镜像尺寸/magic/littlefs/p65Record 断言
# 真机固件在仓库外时可用 DEVLIB_P65_FW=/path/to/fw 指过去（缺省自动跳过依赖固件的断言）
```

> 脚本由 devicecore 提供；`tools/build_devlib.sh` 尚未合入时 host-tests 会自动 SKIP。
> 构建成功后 `build/devlib/mkdevice self-test` 会自检；需要真机固件的检查在 CI 上自动跳过。

### 1.4 QEMU 宿主构建（仅排障，不推荐；CI 的 firmware-smoke 已覆盖真实开机验证）

```bash
# 脚本由 qemu-core 提供；本地执行仅排障，CI 的 firmware-smoke 已覆盖
QEMU_SRC=work/ci-release/qemu-src bash scripts/ci/build-qemu-native.sh work/ci-release/qemu-native
# 产物：work/ci-release/qemu-native/qemu-system-arm
```

依赖（Ubuntu）：`libglib2.0-dev libpixman-1-dev libpng-dev meson ninja-build pkg-config build-essential`
（`libpng-dev` 是 QMP `screendump format=png` 的编译期依赖；缺了截图会失败）。
脚本内部会 clone/复用固定 commit 的 qemu-miwear（`dp-1700-exp`），可重复调用（增量构建）。

只做 Android 侧时不需要 QEMU 源码构建：CI 的 `qemu-android.yml` 产出 NDK 交叉编译产物，
`firmware-smoke.yml` 在 CI 上完成宿主 QEMU 编译 + 真实固件开机验证。

## 2. CI 全流程

| Workflow | 触发 | 耗时 | 产物 |
| --- | --- | --- | --- |
| `android.yml` | push/PR（app/core/gradle 相关路径）、手动 | ~2–3 min | Artifact `mirage-droid-debug`（debug APK）、`android-test-report` |
| `host-tests.yml` | push/PR（相关路径）、手动 | ~3–6 min | Artifact `junit-report`、`devicelib-logs` |
| `qemu-android.yml` | `core/ndk/**`、`scripts/ci/**`、自身变化 / 手动（qemu-core） | 20–40 min（ccache 命中后 5–10 min） | Artifact `qemu-android-arm64`（`libqemu-system-arm.so` + 依赖，arm64-v8a）、失败时 `qemu-android-logs` |
| `apk-with-core.yml` | `qemu-android` 成功后自动 / 手动（可指定 run_id）/ push main 可选 | ~5–8 min | Artifact `mirage-droid-core-debug`（**可真开机的 APK** + BUILD-INFO + SHA256SUMS） |
| `firmware-smoke.yml` | **仅手动** `workflow_dispatch` | 30–60 min | 截图 PNG + UART/QEMU/console 日志（**唯一真实开机验证通道**；固件不入 artifact） |
| `release.yml` | tag `v*`、手动 | ~4–6 min | GitHub Release（APK + 源码快照 + 校验/合规文件） |

查看运行：

```bash
gh run list -R Sakurakilove/mirage-droid --limit 10
gh run view <run-id> -R Sakurakilove/mirage-droid --log
```

### 2.1 host-tests 三个 job

1. `workflow-lint`：`scripts/release/validate_workflows.py`（PyYAML 结构）+ `actionlint`（固定 1.7.12，
   带 SHA-256 校验；runner 自带 shellcheck，会一并检查 `run:` 脚本）。
2. `jvm-unit`：`gradle :app:testDebugUnitTest`，JUnit 报告上传，失败可按用例定位。
3. `host-devicelib`：`bash tools/build_devlib.sh`（gcc）+ 单测 + Python lint。
   devicelib 脚本未合入时打印 `::warning ... SKIP`，**不挡 android 的绿**。

### 2.1.1 固件冒烟（`firmware-smoke.yml`，唯一真实开机验证）

- 手动触发，输入固件直链（`firmware_url`，或 `vela_ap_url`+`vela_resource_url`）、`profile`、超时、非黑阈值。
- CI 上完成：下载固件到 `$RUNNER_TEMP`（**不缓存、不上传**）→ apt 装 glib/pixman/libpng/meson →
  `tools/build_devlib.sh`（gcc 编 mkdevice）→ `scripts/ci/build-qemu-native.sh`（宿主 QEMU）→
  `mkdevice provision`+`verify` → 启动 QEMU → 每 5s QMP `screendump` → `png-nonblack.py` 判定非全黑。
- 失败会打印 UART/QEMU/console 日志尾部并把截图与日志上传 artifact；固件与镜像永不进入 artifact。

### 2.2 带核心 APK（真开机交付，`apk-with-core.yml`）

- 普通 `android.yml` 产出的是不含模拟核心的空壳 APK；**能让固件真正跑起来的 APK 走 `apk-with-core.yml`**。
- 触发：`qemu-android` 成功后自动（`workflow_run`），或手动 `gh workflow run apk-with-core.yml`
  （可传 `run_id`；留空自动取最近一次成功的 qemu-android run）。
- 流程：下载 qemu artifact → 解包校验（AArch64/bionic/`Board profiles`/无 glibc/大小）→
  `lib/*.so` → `app/src/main/jniLibs/arm64-v8a/`、keymaps → `app/src/main/assets/qemu/pc-bios/keymaps/` →
  `assembleDebug` → APK 内断言（主 .so 存在且 ≥1 MiB、.so 数量、keymaps）→ 上传 `mirage-droid-core-debug`。
- 注意：`app/src/main/jniLibs/**` 与 `app/src/main/assets/qemu/**` 是构建期生成物（`.gitignore` 已忽略），
  本地不要手放；keymaps 缺失只告警，主 .so 缺失会 fail。
- 安装到真机：下载 artifact → `adb install -r mirage-droid-core-debug.apk`，再在 App 内导入固件。

### 2.3 release 流程

1. 触发：push tag `v*`；或手动 `Actions → release → Run workflow`（输入 `tag`，可选 `dry_run/draft/prerelease`）。
2. 构建：有签名 Secrets → `assembleRelease` + `zipalign`/`apksigner`；无 → `assembleDebug`（debug 签名，
   release notes 标注仅供测试）。
3. 打包：`git archive` 源码快照、`BUILD-INFO.txt`、`SHA256SUMS`、`RELEASE_NOTES.md`、
   `SOURCE-OFFER.md`（AGPL/GPL 合规）、`LICENSE`。
4. 发布：`gh release create`（已存在则 `upload --clobber` + `edit`），APK/源码/校验/合规文件为资产。
5. 演练：`dry_run=true` 只构建并上传 artifact，不创建 Release。

```bash
# 手动演练（不创建 Release）
gh workflow run release.yml -R Sakurakilove/mirage-droid -f tag=v0.0.0-ci -f dry_run=true
```

## 3. 产物位置一览

| 产物 | 本地 | CI |
| --- | --- | --- |
| debug APK | `app/build/outputs/apk/debug/*.apk` | Artifact `mirage-droid-debug` |
| JUnit 报告 | `app/build/reports/tests/testDebugUnitTest/` | Artifact `junit-report` |
| devicelib 二进制 | 由 `tools/build_devlib.sh` 决定 | 构建日志 artifact `devicelib-logs` |
| 镜像（NOR/NAND） | `mkdevice provision --device <dir>` 输出的 `nor.img`/`nand.img` | 仅在 firmware-smoke 临时目录（不保存） |
| 带核心 APK（真开机） | —（CI 生成） | Artifact `mirage-droid-core-debug`（APK + BUILD-INFO + SHA256SUMS） |
| 发布 APK/源码 | `dist/`（本地模拟流程时） | GitHub Release assets（`mirage-droid-<ver>.apk` 等） |

## 4. 固件策略（重要）

- 固件（`vela_ap.bin` / `vela_resource.bin` / OTA zip）**不入库、不进 artifact、不缓存**。
- `firmware-smoke.yml` 只在手动触发时把固件下载到 runner 的 `$RUNNER_TEMP`，运行结束随 runner 销毁；
  不要给该流程加 `actions/cache` 或 upload-artifact 固件路径。
- 本地固件放仓库外（如 `~/work/fw/`），`test.sh` 等脚本如需路径请用环境变量传入。

## 5. 常见失败排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `SDK location not found` | 设置 `ANDROID_HOME`/`ANDROID_SDK_ROOT`，或本地写 `local.properties` 的 `sdk.dir` |
| `gradle: command not found` | 未装 Gradle；CI 由 setup-gradle 提供。本地用 8.10.2（或临时 `sdk install gradle 8.10.2`） |
| Gradle 下载依赖很慢/失败 | CI 已带缓存；本地可配置镜像。PR 上缓存只读属预期 |
| `:app:testDebugUnitTest NO-SOURCE` | 没有测试源时正常；host-tests 会等 devicecore/android-app 补测试 |
| APK 上传报 `if-no-files-found` | `assembleDebug` 未产出；查构建日志，确认 `ANDROID_HOME` 生效 |
| APK 体积告警（>100 MiB） | 检查是否误打包 qemu/固件/大资源；`android.yml` 的 APK 大小报告在运行摘要里 |
| release 签名失败 | 4 个 `KEYSTORE_*` Secrets 必须同时存在；`base64 -d` 能还原 keystore；本地复现：`KEYSTORE_FILE=… bash scripts/release/sign-apk.sh some.apk` |
| actionlint 报错 | 先跑 `python3 scripts/release/validate_workflows.py` 看结构错误；再用固定版本 actionlint 复核 |
| 没有 `mirage-droid-core-debug` artifact | `qemu-android` 还没成功；或 `apk-with-core.yml` 跳过（看运行摘要）。手动：`gh workflow run apk-with-core.yml` |
| `apk-with-core` 报“缺少主程序” | qemu artifact 布局不对（`qemu-android` 的 package 步骤产物异常）；查看上一步 `/qemu-artifact` 文件列表 |
| `apk-with-core` 警告缺 keymaps | qemu 产物没带 `share/qemu/keymaps/en-us`；APK 仍可启动，但 VNC 键盘不可用 |
| `firmware-smoke` 报“依赖缺失” | 缺少 `scripts/ci/build-qemu-native.sh` 或 `tools/build_devlib.sh`（已合入；若再出现说明回退/分支不对）。**已收紧为 fail，不再 SKIP** |
| 固件相关步骤失败但日志无链接 | 固件 URL 需是直链（`curl -fL` 可下载），且 OTA zip 内含 `vela_ap.bin`/`vela_resource.bin` |

## 6. 相关文档

- workflows 一览与 Secrets：[`.github/workflows/README.md`](../.github/workflows/README.md)
- 架构与里程碑：[`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
- 许可与合规：[`docs/COMPLIANCE.md`](COMPLIANCE.md)
