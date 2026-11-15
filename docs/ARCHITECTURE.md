# 架构与里程碑

## 目标
安卓手机端原生模拟器：真实手表固件在手机上跑起来，并提供类官方应用的设备管理界面。
优先 **Redmi Watch 6（p65）** 与 **小米手环 11（q66cn）**。

## 分层

```
Android app (Kotlin, Views + Canvas)
├── ui/        设备库、设备页（屏幕 Stage + 控制条）、控制台、表盘管理、设置、关于
├── emu/       SessionManager：进程生命周期、端口/套接字、崩溃回收、日志
├── net/       VncClient(RFB 3.8/Raw)、QmpClient(JSON)、NshClient(console chardev)
├── devicelib/ OTA 导入、DeviceProfile(p65/q66/…)、ImageBuilder(NOR/NAND/GPT/littlefs)
└── jni/       libmiragedroid.so：littlefs、镜像写入、ROMFS，native 入口

core/
├── qemu/              submodule: qemu-miwear @ dp-1700-exp（GPL-2.0）
├── littlefs/          BSD-3-Clause
├── devicelib/         C 实现：镜像构建 CLI（host 与 Android 共用）
└── ndk/               glib / pixman / QEMU 的 NDK 交叉构建

.github/workflows/
├── android.yml        Gradle APK（缓存 + 单元测试）
├── qemu-android.yml   NDK 交叉编译 qemu-system-arm（arm64-v8a / x86_64）
├── host-tests.yml     devicelib CLI、镜像字节断言、Python/C 单测
└── firmware-smoke.yml 手动：真实固件开机冒烟（固件不入库）
```

## 运行时形态

- QEMU 以子进程运行：`-M miwear,profile=p65 -kernel vela_ap.bin -display none -vnc unix:… -qmp unix:… -chardev socket,…`
- 屏幕：VNC RFB 3.8 + Raw 编码 → SurfaceView
- 触摸：VNC PointerEvent；按键/电量：QMP `input-send-event` / QOM
- 控制台：console chardev socket（P65 有 NSH；q66 仅日志）
- 二进制打包：`libqemu-system-arm.so`（去掉 lib 前缀，装进 jniLibs，native 目录可执行）

## 里程碑
| # | 内容 | 验收 |
|---|---|---|
| M0 | 仓库 + CI 骨架，空壳 APK | Actions 产出 `mirage-droid-debug.apk` |
| M1 | qemu-system-arm 交叉编译（NDK） | Termux 同配方真实开机 P65；CI 产物 aarch64/Android、`profile=help` 含 p65/q66 |
| M2 | devicelib：OTA 导入 + 镜像构建 | 单测字节断言；本地 QEMU 用生成镜像开机 |
| M3 | 会话层：VNC/QMP/console | 启动→取帧→点击→截图→关机 全链路脚本通过 |
| M4 | UI：设备库/设备页/控制台/设置 | 真机 screencap 三页 + 触摸可用 |
| M5 | 表盘安装/切换 | P65 NSH 注入可用；q66 离线注入 |
| M6 | 发布 | Release APK + AGPL/GPL 合规附件 |
