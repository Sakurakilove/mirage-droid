# 设备 Profile（权威值，取自 Mirage `DeviceProfile.swift` @ main）

qemu 机器名统一：`-M miwear,profile=<qemuBoard>`。console UART 编号决定 NSH 走哪个串口。

## Redmi Watch 6（p65）— 首要目标

| 项 | 值 |
|---|---|
| qemuBoard / chip | `p65` / `best1502p` |
| product 前缀 | `miwear.watch.p65` |
| 面板 | **432 × 514**，圆角 48（rm690c0 驱动，Zinitix 触摸 I2C5） |
| 按键 | `home`（上键，placement rightUpper）、`pgdn`（电源键，rightLower） |
| console UART | **1** |
| 主 NOR | `flash-image=`，是 |
| SPI NAND | page 2048 / oob 64 / pagesPerBlock 64 / blocks 4096；`vela_resource.bin` 从 page 0 起、**112640 pages**；`/data` **149504 pages** |
| 工厂分区 | NOR 偏移 `0xCC0000`，blockSize 256，1 block，ioSize 256，格式 **p65Record**（256 B 定长记录） |
| 出厂字段 | did 前缀 `1502`、psn 前缀 `QEMU1502`、SKU `M2509W1` |

## 小米手环 11（q66）— 首要目标

| 项 | 值 |
|---|---|
| qemuBoard / chip | `q66` / `best1503` |
| product 前缀 | `miwear.watch.q66` |
| 面板 | **212 × 520**，圆角 104（CST92xx 触摸 I2C5） |
| 按键 | 无 |
| console UART | **2** |
| 主 NOR | `flash-image=`，是 |
| SPI NAND | page 2048 / oob 64 / pagesPerBlock 64 / blocks 4096；`vela_resource.bin` page 0 起、**65536 pages**；`/data` **196608 pages** |
| 工厂分区 | NOR 偏移 `32256*512 = 0xFC0000`，blockSize 8192，16 blocks，ioSize 512，**littlefs 2.1**（`0x00020001`） |

## 其他内置（同一套代码直接可用）

| id | 设备 | board / chip | 面板 | console UART |
|---|---|---|---|---|
| p67 | 小米手环 10 Pro | p67 / best1503 | 336×480 圆角48 | 2 |
| q63 | 小米手表 S5 | q63 / best1700 | 466×466 圆形 | 0 |
| n66 | 小米手环 9 | n66 / best1503 | 192×490 圆角96 | 2 |
| o66 | 小米手环 10 | o66 / best1503 | 212×520 圆角104 | 2 |
| n67 | 小米手环 9 Pro | n67 / best1503 | 336×480 圆角48 | 2 |
| q65 | REDMI Watch 7 | q65 / best1502p | 432×514 圆角48 | 1 |

## 启动命令（Mirage `LaunchPlan.make` 等价）

```
qemu-system-arm \
  -M miwear,profile=p65,flash-image=<dev>/nor.img,nand-image=<dev>/nand.img \
  -kernel <fw>/vela_ap.bin \
  -name "MirageDroid: <device>" \
  -display none \
  -vnc unix:<rt>/vnc.sock \
  -qmp unix:<rt>/qmp.sock \
  -qmp unix:<rt>/qmp-aux.sock \
  -monitor none \
  -chardev socket,id=con,path=<rt>/console.sock,server=on,wait=off,logfile=<rt>/console.log \
  -serial file:<rt>/uart0.log -serial chardev:con -serial file:<rt>/uart2.log \
  [电量] -M 追加 ,battery-level=<0-100>,battery-charging=on|off
```

- 屏幕：VNC RFB 3.8，取帧后按 profile 的宽高/圆角绘制；
- 触摸：VNC PointerEvent（按 VNC 面板像素坐标，不是手机屏幕坐标）；
- 按键：QMP `input-send-event`（qcode 见上表）；
- 控制台：console.sock 文本（p65 有 NSH，q66 只有日志）。
