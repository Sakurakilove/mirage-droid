package com.zhaoge.miragedroid.data

import java.io.File
import java.io.FileOutputStream

/**
 * App 的落盘数据层：设备列表 / 固件列表 / 全局设置，全部是 filesDir 下的 JSON。
 *
 * 这一层只依赖 java.io.File，不碰 Android API，因此可以在 JVM 单测里用临时目录
 * 完整验证（序列化、原子写、损坏文件降级）。
 */
class DeviceStore(val root: File) {
    private val devicesFile = File(root, "devices.json")
    private val firmwaresFile = File(root, "firmware.json")
    private val settingsFile = File(root, "settings.json")

    val devicesRoot: File get() = File(root, "devices")
    val firmwaresRoot: File get() = File(root, "firmware")
    val exportsRoot: File get() = File(root, "exports")

    fun ensureDirectories() {
        root.mkdirs()
        devicesRoot.mkdirs()
        firmwaresRoot.mkdirs()
        exportsRoot.mkdirs()
    }

    fun deviceDir(deviceId: String): File = File(devicesRoot, deviceId)

    fun firmwareDir(firmwareId: String): File = File(firmwaresRoot, firmwareId)

    // ------------------------------------------------------------ 设备

    @Synchronized
    fun devices(): List<VirtualDevice> {
        val text = readText(devicesFile) ?: return emptyList()
        val array = Json.parseOrNull(text) as? JsonValue.Arr ?: return emptyList()
        return array.items.mapNotNull { VirtualDevice.fromJson(it) }
    }

    @Synchronized
    fun device(deviceId: String): VirtualDevice? = devices().firstOrNull { it.id == deviceId }

    @Synchronized
    fun saveDevices(devices: List<VirtualDevice>) {
        ensureDirectories()
        writeAtomic(devicesFile, jsonArray(devices.map { it.toJson() }).encode())
    }

    @Synchronized
    fun upsertDevice(device: VirtualDevice): List<VirtualDevice> {
        val current = devices().toMutableList()
        val index = current.indexOfFirst { it.id == device.id }
        if (index >= 0) current[index] = device else current.add(device)
        saveDevices(current)
        return current
    }

    /** 删除设备记录；[deleteFiles] 为真时连镜像与日志一起删。 */
    @Synchronized
    fun deleteDevice(deviceId: String, deleteFiles: Boolean = true): Boolean {
        val current = devices().toMutableList()
        val removed = current.removeAll { it.id == deviceId }
        if (removed) saveDevices(current)
        if (deleteFiles) deviceDir(deviceId).deleteRecursively()
        return removed
    }

    // ------------------------------------------------------------ 固件

    @Synchronized
    fun firmwares(): List<FirmwareRecord> {
        val text = readText(firmwaresFile) ?: return emptyList()
        val array = Json.parseOrNull(text) as? JsonValue.Arr ?: return emptyList()
        return array.items.mapNotNull { FirmwareRecord.fromJson(it) }
    }

    @Synchronized
    fun firmware(firmwareId: String): FirmwareRecord? = firmwares().firstOrNull { it.id == firmwareId }

    @Synchronized
    fun saveFirmwares(firmwares: List<FirmwareRecord>) {
        ensureDirectories()
        writeAtomic(firmwaresFile, jsonArray(firmwares.map { it.toJson() }).encode())
    }

    @Synchronized
    fun upsertFirmware(firmware: FirmwareRecord): List<FirmwareRecord> {
        val current = firmwares().toMutableList()
        val index = current.indexOfFirst { it.id == firmware.id }
        if (index >= 0) current[index] = firmware else current.add(firmware)
        saveFirmwares(current)
        return current
    }

    @Synchronized
    fun deleteFirmware(firmwareId: String, deleteFiles: Boolean = true): Boolean {
        val current = firmwares().toMutableList()
        val removed = current.removeAll { it.id == firmwareId }
        if (removed) saveFirmwares(current)
        // 解绑引用了该固件的设备，避免 UI 显示悬空引用
        val devices = devices().toMutableList()
        var devicesChanged = false
        for (index in devices.indices) {
            if (devices[index].firmwareId == firmwareId) {
                devices[index] = devices[index].copy(firmwareId = null, storageBuiltAt = 0L)
                devicesChanged = true
            }
        }
        if (devicesChanged) saveDevices(devices)
        if (deleteFiles) firmwareDir(firmwareId).deleteRecursively()
        return removed
    }

    // ------------------------------------------------------------ 设置

    @Synchronized
    fun settings(): AppSettings {
        val text = readText(settingsFile) ?: return AppSettings.DEFAULTS
        val value = Json.parseOrNull(text) ?: return AppSettings.DEFAULTS
        return AppSettings.fromJson(value)
    }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        ensureDirectories()
        writeAtomic(settingsFile, settings.toJson().encode())
    }

    @Synchronized
    fun updateSettings(block: (AppSettings) -> AppSettings): AppSettings {
        val updated = block(settings())
        saveSettings(updated)
        return updated
    }

    // ------------------------------------------------------------ 工具

    companion object {
        fun readText(file: File): String? = try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }

        /** 先写 .tmp 再 rename，避免进程被杀时留下半截 JSON。 */
        fun writeAtomic(file: File, text: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        file.writeText(text, Charsets.UTF_8)
                        tmp.delete()
                    }
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
