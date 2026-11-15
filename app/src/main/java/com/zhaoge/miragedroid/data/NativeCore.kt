package com.zhaoge.miragedroid.data

import java.io.File

/**
 * 对 [Devicelib]（JNI）的薄封装：负责加载 libmiragedroid.so，并在 native 库
 * 缺失（M0/M1 阶段没有 devicelib 产物）时优雅降级——所有调用返回可读的错误，
 * 而不是把 UnsatisfiedLinkError 抛到界面。
 */
object NativeCore {
    private const val LIBRARY_NAME = "miragedroid"

    private enum class LoadState { UNKNOWN, LOADED, MISSING }

    @Volatile
    private var state = LoadState.UNKNOWN

    @Volatile
    private var loadError: String? = null

    data class NativeResult(val ok: Boolean, val code: Int, val message: String) {
        companion object {
            fun success() = NativeResult(true, 0, "")
        }
    }

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (state != LoadState.UNKNOWN) return state == LoadState.LOADED
        return try {
            System.loadLibrary(LIBRARY_NAME)
            state = LoadState.LOADED
            true
        } catch (t: Throwable) {
            state = LoadState.MISSING
            loadError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun available(): Boolean = ensureLoaded()

    fun loadError(): String? {
        ensureLoaded()
        return loadError
    }

    /** native 层版本字符串；缺失时返回 null。 */
    fun version(): String? {
        if (!ensureLoaded()) return null
        return try {
            Devicelib.version()
        } catch (t: Throwable) {
            null
        }
    }

    fun importOta(zip: File, outDir: File): NativeResult {
        if (!ensureLoaded()) return missingLibrary()
        outDir.mkdirs()
        return try {
            val code = Devicelib.importOta(zip.absolutePath, outDir.absolutePath)
            resultOf(code, "解包 OTA 失败")
        } catch (t: Throwable) {
            NativeResult(false, -1, "调用 devicelib 解包失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun firmwareInfo(firmwareDir: File): FirmwareInfo? {
        if (!ensureLoaded()) return null
        return try {
            val text = Devicelib.firmwareInfo(firmwareDir.absolutePath) ?: return null
            FirmwareInfo.parse(text)
        } catch (t: Throwable) {
            null
        }
    }

    fun buildStorage(profileId: String, firmwareDir: File, deviceDir: File): NativeResult {
        if (!ensureLoaded()) return missingLibrary()
        firmwareDir.mkdirs()
        deviceDir.mkdirs()
        return try {
            val code = Devicelib.buildStorage(profileId, firmwareDir.absolutePath, deviceDir.absolutePath)
            resultOf(code, "生成设备存储失败")
        } catch (t: Throwable) {
            NativeResult(false, -1, "调用 devicelib 生成存储失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun resultOf(code: Int, prefix: String): NativeResult =
        if (code == Devicelib.OK) {
            NativeResult.success()
        } else {
            val detail = try {
                Devicelib.lastError()
            } catch (t: Throwable) {
                ""
            }
            NativeResult(false, code, if (detail.isNullOrBlank()) "$prefix（错误码 $code）" else "$prefix：$detail")
        }

    private fun missingLibrary(): NativeResult = NativeResult(
        ok = false,
        code = -100,
        message = "当前构建不含 devicelib（libmiragedroid.so），无法导入固件或生成设备存储。请安装含模拟核心的构建。",
    )
}
