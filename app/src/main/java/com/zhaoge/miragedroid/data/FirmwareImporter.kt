package com.zhaoge.miragedroid.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 固件导入 + 设备存储构建（SAF 选 OTA zip → devicelib 解包 → 生成 NOR/NAND 镜像）。
 *
 * 所有方法都是阻塞的，调用方必须放在后台线程；进度通过 [progress] 回调。
 * native 库（libmiragedroid.so）缺失时返回明确的中文错误，不抛异常。
 */
class FirmwareImporter(
    private val context: Context,
    private val store: DeviceStore,
) {
    data class ImportResult(val firmware: FirmwareRecord?, val message: String)

    data class ProvisionResult(val ok: Boolean, val message: String)

    /** 从 SAF 选中的 URI 导入 OTA 包。 */
    fun importOta(
        uri: Uri,
        displayNameHint: String? = null,
        progress: (String) -> Unit = {},
    ): ImportResult {
        if (!NativeCore.available()) {
            return ImportResult(
                null,
                "当前构建不含 devicelib（libmiragedroid.so），无法导入固件。" +
                    "请安装含模拟核心的构建（CI 的 qemu/devicelib 产物需要打进 APK）。",
            )
        }
        store.ensureDirectories()
        val cacheDir = File(context.cacheDir, "imports").apply { mkdirs() }
        val sourceName = displayNameHint?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "firmware.zip"
        val tempZip = File(cacheDir, "ota-${System.currentTimeMillis()}.zip")

        return try {
            progress("正在读取 OTA 文件…")
            copyUriToFile(uri, tempZip)

            val firmwareId = UUID.randomUUID().toString()
            val firmwareDir = store.firmwareDir(firmwareId)
            if (firmwareDir.exists()) firmwareDir.deleteRecursively()
            firmwareDir.mkdirs()

            progress("正在解包 OTA（vela_ap.bin / vela_resource.bin）…")
            val unpack = NativeCore.importOta(tempZip, firmwareDir)
            if (!unpack.ok) {
                firmwareDir.deleteRecursively()
                return ImportResult(null, unpack.message)
            }

            progress("正在读取固件信息…")
            val info = NativeCore.firmwareInfo(firmwareDir)
            val apFile = File(firmwareDir, "vela_ap.bin")
            if (!apFile.isFile) {
                firmwareDir.deleteRecursively()
                return ImportResult(null, "OTA 包内没有 vela_ap.bin，可能不是官方的完整 OTA 包。")
            }
            val resourceFile = File(firmwareDir, "vela_resource.bin").takeIf { it.isFile }

            val product = info?.product ?: ""
            val version = info?.version ?: ""
            val profile = DeviceProfiles.builtins.firstOrNull { it.runsProduct(product) }
            val autoName = buildString {
                append(profile?.name ?: product.ifEmpty { "固件" })
                if (version.isNotEmpty()) append(" ").append(version)
            }
            val record = FirmwareRecord(
                id = firmwareId,
                displayName = displayNameHint?.takeIf { it.isNotBlank() } ?: autoName,
                product = product,
                version = version,
                chip = info?.chip ?: "",
                importedAt = System.currentTimeMillis(),
                sourceName = sourceName,
                apFile = "vela_ap.bin",
                resourceFile = resourceFile?.name,
                sizeBytes = directorySize(firmwareDir),
                stage = 2,
            )
            store.upsertFirmware(record)
            ImportResult(record, "固件导入完成：${record.displayName}")
        } catch (t: Throwable) {
            ImportResult(null, "导入失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    /** 用 devicelib 生成/重建一台设备的存储镜像。 */
    fun provision(
        device: VirtualDevice,
        firmware: FirmwareRecord,
        profile: DeviceProfile,
        progress: (String) -> Unit = {},
    ): ProvisionResult {
        if (!NativeCore.available()) {
            return ProvisionResult(
                false,
                "当前构建不含 devicelib（libmiragedroid.so），无法生成设备存储。" +
                    "请安装含模拟核心的构建。",
            )
        }
        val firmwareDir = store.firmwareDir(firmware.id)
        if (!File(firmwareDir, firmware.apFile).isFile) {
            return ProvisionResult(false, "固件文件缺失：${firmwareDir.absolutePath}/${firmware.apFile}，请重新导入固件。")
        }
        val deviceDir = store.deviceDir(device.id)
        deviceDir.mkdirs()
        progress("正在生成 ${profile.name} 的存储镜像（NOR/NAND，首次约几十秒）…")
        val result = NativeCore.buildStorage(profile.id, firmwareDir, deviceDir)
        if (!result.ok) return ProvisionResult(false, result.message)

        val missing = profile.storage.imageFiles.filter { !File(deviceDir, it).isFile }
        if (missing.isNotEmpty()) {
            return ProvisionResult(false, "存储镜像不完整，缺少：${missing.joinToString("、")}")
        }
        val updated = device.copy(
            firmwareId = firmware.id,
            storageBuiltAt = System.currentTimeMillis(),
        )
        store.upsertDevice(updated)
        return ProvisionResult(true, "设备存储已就绪（${profile.storage.imageFiles.joinToString("、")}）。")
    }

    /** 设备当前是否已经具备可启动的镜像。 */
    fun storageReady(device: VirtualDevice, firmware: FirmwareRecord?): Boolean {
        if (firmware == null || !File(store.firmwareDir(firmware.id), firmware.apFile).isFile) return false
        val profile = device.profile()
        val deviceDir = store.deviceDir(device.id)
        return profile.storage.imageFiles.all { File(deviceDir, it).isFile }
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开所选文件")
        input.use { stream ->
            FileOutputStream(destination).use { output ->
                stream.copyTo(output, 256 * 1024)
                output.fd.sync()
            }
        }
        if (destination.length() == 0L) throw IllegalStateException("所选文件为空")
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
