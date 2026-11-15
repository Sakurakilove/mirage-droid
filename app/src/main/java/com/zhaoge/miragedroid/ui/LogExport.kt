package com.zhaoge.miragedroid.ui

import android.content.Context
import com.zhaoge.miragedroid.data.DeviceStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把各设备的日志打包成一个 zip（放 App 专属外部目录，免权限），
 * 便于用户用文件管理器/ADB 取出反馈问题。绝不包含固件与镜像。
 */
object LogExport {
    fun exportAllLogs(context: Context, store: DeviceStore, extraInfo: String): File? = try {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "exports")
        val targetDir = File(base, "logs")
        targetDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(targetDir, "mirage-logs-$stamp.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            val infoEntry = ZipEntry("info.txt")
            out.putNextEntry(infoEntry)
            out.write(extraInfo.toByteArray(Charsets.UTF_8))
            out.closeEntry()

            for (device in store.devices()) {
                val logsDir = File(store.deviceDir(device.id), "Logs")
                if (!logsDir.isDirectory) continue
                val files = logsDir.listFiles() ?: continue
                for (file in files) {
                    if (!file.isFile || file.length() == 0L) continue
                    val entry = ZipEntry("${device.name}-${device.id.take(8)}/${file.name}")
                    out.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                    out.closeEntry()
                }
            }
        }
        zip
    } catch (error: Throwable) {
        null
    }
}
