# 许可与合规

- 本项目整体以 **AGPL-3.0** 发布。
- 内置模拟核心来自 [qemu-miwear](https://github.com/AstralSightStudios/qemu-miwear)（GPL-2.0，QEMU 11.1.2 的板级分支），
  源码随仓库提供（git submodule + `core/ndk/` 全部构建脚本），发布版 APK 的 `关于` 页给出来源地址。
- [littlefs](https://github.com/littlefs-project/littlefs)：BSD-3-Clause。
- 固件（`vela_ap.bin` / `vela_resource.bin`）版权归小米，**不由本仓库分发**；
  用户需自行获取 OTA 包并在 App 内本地导入。CI 不缓存固件。
- 本项目与小米公司无关；「小米运动健康」仅作为交互范式参考，不包含其代码或资源。
