package com.zhaoge.miragedroid.emu

import com.zhaoge.miragedroid.data.DeviceProfile
import com.zhaoge.miragedroid.data.DeviceStore
import java.io.File

/**
 * 一台设备的全部文件位置（对齐 Mirage 的 DevicePaths）：
 * 镜像放在设备目录，日志放 Logs/，unix socket 放 cacheDir 下的短路径目录。
 *
 * unix socket 路径有 104 字节上限，所以运行时目录必须短：cache/rt-<8 位 id>。
 */
class DevicePaths(
    val store: DeviceStore,
    val deviceId: String,
    val cacheDir: File,
) {
    val shortId: String = deviceId.filter { it.isLetterOrDigit() || it == '-' }
        .take(10)
        .ifEmpty { "device" }

    val deviceDir: File get() = store.deviceDir(deviceId)

    val nor: File get() = File(deviceDir, "nor.img")
    val sensorNor: File get() = File(deviceDir, "sensor-nor.img")
    val nand: File get() = File(deviceDir, "nand.img")
    val emmc: File get() = File(deviceDir, "emmc.img")
    val identity: File get() = File(deviceDir, "identity.properties")

    val logs: File get() = File(deviceDir, "Logs")
    val screenshots: File get() = File(deviceDir, "Screenshots")

    val runtimeDir: File get() = File(cacheDir, "rt-$shortId")

    val qmpSocket: File get() = File(runtimeDir, "qmp.sock")

    /** 第二个 QMP 监视器，给外部工具用；镜像 Mirage 的 qmp-aux.sock。 */
    val qmpAuxSocket: File get() = File(runtimeDir, "qmp-aux.sock")
    val vncSocket: File get() = File(runtimeDir, "vnc.sock")
    val consoleSocket: File get() = File(runtimeDir, "console.sock")
    val gdbSocket: File get() = File(runtimeDir, "gdb.sock")

    val pidFile: File get() = File(deviceDir, "qemu.pid")
    val consoleLog: File get() = File(logs, "console.log")
    val qemuLog: File get() = File(logs, "qemu.log")
    val stdoutLog: File get() = File(logs, "stdout.log")

    fun uartLog(index: Int): File = File(logs, "uart$index.log")

    fun firmwareAp(firmwareDir: File): File = File(firmwareDir, "vela_ap.bin")

    fun firmwareResource(firmwareDir: File): File = File(firmwareDir, "vela_resource.bin")

    /** 建目录并清掉上次异常退出留下的 socket / pid 文件。 */
    fun prepare() {
        deviceDir.mkdirs()
        logs.mkdirs()
        screenshots.mkdirs()
        runtimeDir.mkdirs()
        for (socket in listOf(qmpSocket, qmpAuxSocket, vncSocket, consoleSocket, gdbSocket)) {
            if (socket.exists()) socket.delete()
        }
        if (pidFile.exists()) pidFile.delete()
    }

    /** profile 需要但当前缺失的镜像（空列表表示可以启动）。 */
    fun missingImages(profile: DeviceProfile): List<String> =
        profile.storage.imageFiles.filter { !File(deviceDir, it).isFile }

    fun imageFor(role: String): File = File(deviceDir, role)

    fun allLogs(): List<File> {
        val out = ArrayList<File>()
        if (logs.isDirectory) {
            logs.listFiles()?.sortedBy { it.name }?.let { out.addAll(it) }
        }
        if (stdoutLog.isFile) out.add(stdoutLog)
        return out.distinct()
    }
}
