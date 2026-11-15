# WORKLOG — MirageDroid（幻影模拟器）

> 目标：安卓手机端原生小米穿戴模拟器，优先 **Redmi Watch 6（p65）** 与 **小米手环 11（q66cn）**。
> 所有重型编译在 GitHub Actions；固件只在本地导入、不入库。

## 2026-11-15 M0：仓库 + CI 骨架

- 新建公开仓库 `Sakurakilove/mirage-droid`（AGPL-3.0），Android 工程骨架（AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26 / Views）。
- CI：`.github/workflows/android.yml`，`ubuntu-latest` + runner 预装 SDK + `gradle/actions/setup-gradle`。
  **坑**：`android-actions/setup-android@v3` 在新版 cmdline-tools 下 `tools` 包不存在 → 失败；改成直接用 runner 预装 SDK。
- 首次绿：run `43674275160`，2m21s，产物 `mirage-droid-debug`。
- 真机链路打通：`pm install` 需把 APK 放到 `/data/local/tmp`（`/sdcard` 因 FUSE context 被 system_server 拒读）；
  `scripts/dev/install_apk.sh` 封装 root 安装 + `am start` + `screencap`；`input tap` 可用于 UI 自动化。
- 文档：`docs/ARCHITECTURE.md`、`docs/DEVICES.md`（p65/q66 面板、NAND/NOR/littlefs、启动命令）、`docs/ACCEPTANCE.md`、`docs/COMPLIANCE.md`。

**下一步**：qemu-miwear Termux 实编（qemu-core）；devicelib 镜像构建（devicecore）；App 页面与协议层（android-app）；CI 加固（ci-release）。
