package com.zhaoge.miragedroid.emu

import com.zhaoge.miragedroid.data.DeviceProfile
import com.zhaoge.miragedroid.data.VirtualDevice
import java.io.File
import java.util.Locale

/**
 * 一次启动的完整命令行（纯逻辑，可在 JVM 单测里断言）。
 *
 * 参数与 Mirage 的 LaunchPlan.swift 对齐：
 * ```
 * qemu-system-arm -M miwear,profile=p65[,flash-image=..][,nand-image=..][,battery-level=80,battery-charging=on]
 *   -kernel fw/p65/vela_ap.bin -name "MirageDroid: REDMI Watch 6" -display none
 *   -vnc unix:/.../vnc.sock -qmp unix:/.../qmp.sock,server=on,wait=off
 *   -qmp unix:/.../qmp-aux.sock,server=on,wait=off -monitor none
 *   -chardev socket,id=con,path=/.../console.sock,server=on,wait=off,logfile=/.../console.log
 *   -serial 0=file:uart0.log -serial 1=chardev:con -serial 2=file:uart2.log
 *   [-drive if=sd,index=0,format=raw,file=.../emmc.img]
 *   -global driver=bes.btss,property=bdaddr,value=AA:BB:CC:DD:EE:FF
 *   [-global driver=bes.btss,property=chardev,value=bt]
 *   [-S -gdb unix:/.../gdb.sock]
 *   -D /.../qemu.log
 * ```
 */
data class LaunchPlan(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: File,
    val firmwareImage: File,
    val vncSocket: File,
    val qmpSocket: File,
    val consoleSocket: File,
    val qemuLog: File,
    val vncDisplay: String,
) {
    /** ProcessBuilder 用的完整命令。 */
    fun command(): List<String> = listOf(executable) + arguments

    /** 用于日志/关于页面展示（含引号转义）。 */
    fun commandLine(): String = command().joinToString(" ") { quote(it) }

    companion object {
        fun quote(value: String): String {
            if (value.isEmpty()) return "''"
            val safe = value.all { it.isLetterOrDigit() || it in "_-=+.,:/@%^" }
            if (safe) return value
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}

object LaunchPlanBuilder {
    /** qemu 的机器选项里逗号要写成两个逗号。 */
    fun escapeCommas(value: String): String = value.replace(",", ",,")

    /**
     * 生成启动计划。
     *
     * @param firmwareDir 固件目录（含 vela_ap.bin / vela_resource.bin）
     * @param keymapsDir 可选的 qemu 数据目录（含 keymaps/en-us），没有则忽略
     */
    fun build(
        profile: DeviceProfile,
        device: VirtualDevice,
        paths: DevicePaths,
        firmwareDir: File,
        executable: String,
        keymapsDir: File? = null,
    ): LaunchPlan {
        val args = ArrayList<String>(64)

        val machine = StringBuilder("miwear,profile=")
        machine.append(escapeCommas(profile.qemuBoard))
        if (profile.storage.mainNor) {
            machine.append(",flash-image=").append(escapeCommas(paths.nor.absolutePath))
        }
        if (profile.storage.sensorNorSize != null) {
            machine.append(",sensor-nor=").append(escapeCommas(paths.sensorNor.absolutePath))
        }
        if (profile.storage.nand != null) {
            machine.append(",nand-image=").append(escapeCommas(paths.nand.absolutePath))
        }
        val batteryLevel = device.batteryLevel.coerceIn(0, 100)
        machine.append(",battery-level=").append(batteryLevel)
        machine.append(",battery-charging=").append(if (device.batteryCharging) "on" else "off")

        val ap = paths.firmwareAp(firmwareDir)
        args += "-M"
        args += machine.toString()
        args += "-kernel"
        args += ap.absolutePath
        args += "-name"
        args += "MirageDroid: ${device.name}"
        args += "-display"
        args += "none"
        args += "-vnc"
        args += "unix:" + paths.vncSocket.absolutePath
        args += "-qmp"
        args += "unix:" + paths.qmpSocket.absolutePath + ",server=on,wait=off"
        args += "-qmp"
        args += "unix:" + paths.qmpAuxSocket.absolutePath + ",server=on,wait=off"
        args += "-monitor"
        args += "none"
        args += "-chardev"
        args += "socket,id=con,path=" + escapeCommas(paths.consoleSocket.absolutePath) +
            ",server=on,wait=off,logfile=" + escapeCommas(paths.consoleLog.absolutePath)

        val serialCount = maxOf(profile.uartCount, profile.consoleUart + 1)
        for (index in 0 until serialCount) {
            args += "-serial"
            args += if (index == profile.consoleUart) {
                "chardev:con"
            } else {
                "file:" + escapeCommas(paths.uartLog(index).absolutePath)
            }
        }

        if (profile.storage.emmc != null) {
            args += "-drive"
            args += "if=sd,index=0,format=raw,file=" + escapeCommas(paths.emmc.absolutePath)
        }

        args += "-global"
        args += "driver=bes.btss,property=bdaddr,value=" + device.bluetoothAddress.uppercase(Locale.US)

        if (device.bluetoothBridge && device.bluetoothPort > 0) {
            args += "-chardev"
            args += "socket,id=bt,host=127.0.0.1,port=${device.bluetoothPort},server=on,wait=off"
            args += "-global"
            args += "driver=bes.btss,property=chardev,value=bt"
        }

        if (device.shell) {
            args += "-S"
            args += "-gdb"
            args += "unix:" + paths.gdbSocket.absolutePath + ",server=on,wait=off"
        }

        if (keymapsDir != null && File(keymapsDir, "keymaps/en-us").isFile) {
            args += "-L"
            args += keymapsDir.absolutePath
        }

        args += "-D"
        args += paths.qemuLog.absolutePath

        for (extra in device.extraArguments) {
            if (extra.isNotBlank()) args += extra
        }

        return LaunchPlan(
            executable = executable,
            arguments = args,
            workingDirectory = paths.runtimeDir,
            firmwareImage = ap,
            vncSocket = paths.vncSocket,
            qmpSocket = paths.qmpSocket,
            consoleSocket = paths.consoleSocket,
            qemuLog = paths.qemuLog,
            vncDisplay = "unix:" + paths.vncSocket.absolutePath,
        )
    }
}
