# MirageDroid — 幻影模拟器

> 安卓手机端原生小米穿戴模拟器（优先 **Redmi Watch 6 / P65** 与 **小米手环 11 / q66cn**）
> 模拟核心：移植 [qemu-miwear](https://github.com/AstralSightStudios/qemu-miwear)（QEMU 11.1.2）
> 交互范式参考小米官方「小米运动健康」，不内嵌、不修改其资源
> 作者：**朝歌**

## 状态

🚧 开发中。里程碑见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 它是什么

把真实手表固件跑在你的安卓手机上：

1. 本地导入官方 OTA 固件包（`vela_ap.bin` / `vela_resource.bin`，不入库、不分发）；
2. 按设备 Profile 生成 NOR / NAND / littlefs 存储镜像；
3. 启动内置 `qemu-system-arm`（编译产物由 GitHub Actions 交叉编译，见 `.github/workflows/`）；
4. 通过 VNC 取帧显示到手机屏幕，触摸/按键/电量经 VNC + QMP 注入；
5. 类官方应用的设备管理界面：设备库、屏幕镜像、控制台（NSH）、表盘管理、截图。

## 构建

所有重型编译都在 GitHub Actions 上完成（NDK 交叉编译 glib / pixman / QEMU + Gradle 打包），
本地只需 Java 21（可选）。

```bash
# 本地（可选）：只跑纯 JVM 测试
gradle :app:testDebugUnitTest

# APK 由 CI 产出：Actions → android → Artifacts
```

## 许可

- 本项目：**AGPL-3.0**（见 `LICENSE`）
- `qemu-miwear`：GPL-2.0；QEMU：GPL-2.0；littlefs：BSD-3-Clause
- 发布 APK 同时提供完整源码与构建脚本，见 `docs/COMPLIANCE.md`

本项目与小米公司无关。固件版权归小米所有，用户需自行合法获取并在本地导入。
