package com.zhaoge.miragedroid.data

import java.util.Locale
import java.util.UUID

/** 导入过的固件（一份 OTA 包解包后的产物）。 */
data class FirmwareRecord(
    val id: String,
    /** 界面显示名，例如 “REDMI Watch 6 v3.100.043”。 */
    val displayName: String,
    /** boot info 里的产品名，例如 miwear.watch.p65。 */
    val product: String,
    val version: String,
    val chip: String,
    val importedAt: Long,
    /** 原始 zip 文件名，仅作展示。 */
    val sourceName: String,
    /** 相对 firmware 目录的路径（vela_ap.bin 等）。 */
    val apFile: String,
    val resourceFile: String?,
    val sizeBytes: Long,
    /** devicelib 是否已经成功解包（1=zip 已解，2=元信息已读）。 */
    val stage: Int,
) {
    val hasAp: Boolean get() = apFile.isNotEmpty()

    fun toJson(): JsonValue.Obj = jsonObject(
        "id" to js(id),
        "displayName" to js(displayName),
        "product" to js(product),
        "version" to js(version),
        "chip" to js(chip),
        "importedAt" to jn(importedAt),
        "sourceName" to js(sourceName),
        "apFile" to js(apFile),
        "resourceFile" to js(resourceFile),
        "sizeBytes" to jn(sizeBytes),
        "stage" to jn(stage),
    )

    companion object {
        fun fromJson(value: JsonValue): FirmwareRecord? {
            val id = value.strOrNull("id") ?: return null
            return FirmwareRecord(
                id = id,
                displayName = value.str("displayName", id),
                product = value.str("product"),
                version = value.str("version"),
                chip = value.str("chip"),
                importedAt = value.long("importedAt"),
                sourceName = value.str("sourceName"),
                apFile = value.str("apFile"),
                resourceFile = value.strOrNull("resourceFile"),
                sizeBytes = value.long("sizeBytes"),
                stage = value.int("stage"),
            )
        }
    }
}

/**
 * 虚拟设备：一台被模拟的硬件实例。
 * 设备身份（did/psn/蓝牙 MAC）由 devicelib 写进 [deviceDir]/identity.properties，
 * 这里只需记住蓝牙 MAC 用于 qemu 的 -global 参数。
 */
data class VirtualDevice(
    val id: String,
    val profileId: String,
    val name: String,
    val firmwareId: String?,
    val createdAt: Long,
    /** 蓝牙 MAC，形如 AA:BB:CC:DD:EE:FF。 */
    val bluetoothAddress: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val shell: Boolean,
    val bluetoothBridge: Boolean,
    val bluetoothPort: Int,
    val extraArguments: List<String>,
    val lastBootedAt: Long,
    /** 存储镜像最后一次构建完成的时间（0 = 还没构建）。 */
    val storageBuiltAt: Long,
) {
    fun profile(): DeviceProfile = DeviceProfiles.require(profileId)

    fun toJson(): JsonValue.Obj = jsonObject(
        "id" to js(id),
        "profileId" to js(profileId),
        "name" to js(name),
        "firmwareId" to js(firmwareId),
        "createdAt" to jn(createdAt),
        "bluetoothAddress" to js(bluetoothAddress),
        "batteryLevel" to jn(batteryLevel),
        "batteryCharging" to jb(batteryCharging),
        "shell" to jb(shell),
        "bluetoothBridge" to jb(bluetoothBridge),
        "bluetoothPort" to jn(bluetoothPort),
        "extraArguments" to extraArguments.toJsonArray(),
        "lastBootedAt" to jn(lastBootedAt),
        "storageBuiltAt" to jn(storageBuiltAt),
    )

    companion object {
        const val DEFAULT_BATTERY = 80

        fun create(profile: DeviceProfile, name: String, firmwareId: String?): VirtualDevice {
            val now = System.currentTimeMillis()
            return VirtualDevice(
                id = UUID.randomUUID().toString(),
                profileId = profile.id,
                name = name.trim().ifEmpty { profile.name },
                firmwareId = firmwareId,
                createdAt = now,
                bluetoothAddress = randomBluetoothAddress(),
                batteryLevel = DEFAULT_BATTERY,
                batteryCharging = true,
                shell = false,
                bluetoothBridge = false,
                bluetoothPort = 0,
                extraArguments = emptyList(),
                lastBootedAt = 0L,
                storageBuiltAt = 0L,
            )
        }

        /** 生成一个本地管理用的随机 MAC（不可与真机冲突，仅供模拟）。 */
        fun randomBluetoothAddress(): String {
            val rnd = java.util.Random()
            val bytes = ByteArray(6)
            rnd.nextBytes(bytes)
            // 本地管理位（bit1）置 1，避免与真实厂商地址段冲突
            val first = (bytes[0].toInt() and 0xFF) or 0x02
            val sb = StringBuilder(17)
            sb.append(String.format(Locale.US, "%02X", first and 0xFE))
            for (i in 1..5) {
                sb.append(':')
                sb.append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
            }
            return sb.toString()
        }

        fun fromJson(value: JsonValue): VirtualDevice? {
            val id = value.strOrNull("id") ?: return null
            val profileId = value.strOrNull("profileId") ?: return null
            return VirtualDevice(
                id = id,
                profileId = profileId,
                name = value.str("name", profileId),
                firmwareId = value.strOrNull("firmwareId"),
                createdAt = value.long("createdAt"),
                bluetoothAddress = value.str("bluetoothAddress"),
                batteryLevel = value.int("batteryLevel", DEFAULT_BATTERY),
                batteryCharging = value.bool("batteryCharging", true),
                shell = value.bool("shell"),
                bluetoothBridge = value.bool("bluetoothBridge"),
                bluetoothPort = value.int("bluetoothPort"),
                extraArguments = value.stringList("extraArguments"),
                lastBootedAt = value.long("lastBootedAt"),
                storageBuiltAt = value.long("storageBuiltAt"),
            )
        }
    }
}

/** 全局设置（filesDir/settings.json）。 */
data class AppSettings(
    val lastDeviceId: String?,
    val keepScreenOn: Boolean,
    val shellByDefault: Boolean,
    val autoStartSession: Boolean,
    /** 自定义 qemu 可执行文件路径（空 = 用 APK 内置 native 库）。 */
    val qemuPath: String?,
    val firmwareDirectory: String?,
) {
    fun toJson(): JsonValue.Obj = jsonObject(
        "lastDeviceId" to js(lastDeviceId),
        "keepScreenOn" to jb(keepScreenOn),
        "shellByDefault" to jb(shellByDefault),
        "autoStartSession" to jb(autoStartSession),
        "qemuPath" to js(qemuPath),
        "firmwareDirectory" to js(firmwareDirectory),
    )

    companion object {
        val DEFAULTS = AppSettings(
            lastDeviceId = null,
            keepScreenOn = true,
            shellByDefault = false,
            autoStartSession = true,
            qemuPath = null,
            firmwareDirectory = null,
        )

        fun fromJson(value: JsonValue): AppSettings = AppSettings(
            lastDeviceId = value.strOrNull("lastDeviceId"),
            keepScreenOn = value.bool("keepScreenOn", true),
            shellByDefault = value.bool("shellByDefault", false),
            autoStartSession = value.bool("autoStartSession", true),
            qemuPath = value.strOrNull("qemuPath"),
            firmwareDirectory = value.strOrNull("firmwareDirectory"),
        )
    }
}

/** devicelib 解析 vela_ap.bin 得到的元信息（firmwareInfo 返回的 JSON）。 */
data class FirmwareInfo(
    val product: String,
    val chip: String,
    val version: String,
    val flashSize: Long,
    val products: List<String>,
) {
    companion object {
        fun fromJson(value: JsonValue): FirmwareInfo = FirmwareInfo(
            product = value.str("product"),
            chip = value.str("chip"),
            version = value.str("version"),
            flashSize = value.long("flashSize"),
            products = value.stringList("products"),
        )

        fun parse(text: String): FirmwareInfo? {
            val root = Json.parseOrNull(text) ?: return null
            val info = fromJson(root)
            if (info.product.isEmpty() && info.products.isEmpty()) return null
            return info
        }
    }
}

/** 运行期会话状态（不落盘）。 */
enum class SessionState(val title: String) {
    STOPPED("已关机"),
    LAUNCHING("正在启动…"),
    RUNNING("运行中"),
    STOPPING("正在关机…"),
    ERROR("启动失败"),
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
