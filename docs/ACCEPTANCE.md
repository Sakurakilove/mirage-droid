# 验收清单

## A. CI（每次 push 必须绿）
- [ ] `android.yml`：`gradle :app:assembleDebug` + `testDebugUnitTest` 成功，上传 `mirage-droid-debug`
- [ ] `host-tests.yml`：devicelib host 构建 + 单测 + python lint 成功（脚本未落地时打印 SKIP）
- [ ] `qemu-android.yml`：NDK 交叉编译成功，产物 `qemu-android-arm64`
- [ ] 所有 workflow 的 YAML 通过校验（`python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/*.yml`）

## B. 真机（本机 Termux + root）
- [ ] `bash scripts/dev/install_apk.sh work/ci/app-debug.apk` 返回 `Success` 并出现 App 界面
- [ ] `screencap` 截图非黑、界面无崩溃（`su -c "dumpsys activity top | head"` 无 crash）
- [ ] App 设备库能新建 p65 / q66 设备
- [ ] 无 native 核心时提示清晰（不是崩溃）

## C. 固件（本地，需用户自备 OTA）
- [ ] P65：工作目录里放 `vela_ap.bin` / `vela_resource.bin` → 建 NOR/NAND 镜像 → qemu 启动 → VNC 首帧非黑 → 能看到表盘
- [ ] P65：触摸点击有响应；`home`/`pgdn` 按键有响应；QMP `screendump` 出 PNG
- [ ] P65：console.sock 能执行 NSH（`echo MIRAGE_OK`）
- [ ] q66：同流程启动、首帧非黑；无按键、无 NSH（只有串口日志）
- [ ] 截图归档到 `work/evidence/<日期>/`（不入库）

## D. 交付物
- [ ] 仓库 `main` 全绿；tag `v0.1.0` 触发 `release.yml`，Release 页有 APK + 合规说明
- [ ] README 与实际一致（构建步骤、能力边界、固件不随包分发）
- [ ] `docs/COMPLIANCE.md` 列全 GPL/AGPL 来源与源码获取方式
