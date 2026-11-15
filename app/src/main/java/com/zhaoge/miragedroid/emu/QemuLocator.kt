package com.zhaoge.miragedroid.emu

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 找到可执行的 qemu-system-arm。
 *
 * Android 上的约定：qemu 主程序被改名成 `libqemu-system-arm.so` 放进 jniLibs，
 * 安装后位于 applicationInfo.nativeLibraryDir（APK 解压出来的只读目录，可执行）。
 * 也支持用户在设置里填自定义路径（Termux/ADB 调试用）。
 */
object QemuLocator {
    /** jniLibs 里的名字：必须 lib*.so 结尾，Android 才会解压到 nativeLibraryDir。 */
    const val PRIMARY_NAME = "libqemu-system-arm.so"

    /** 兼容直接放 qemu-system-arm 的构建（如测试版 APK）。 */
    const val FALLBACK_NAME = "qemu-system-arm"

    data class Candidate(val label: String, val file: File, val exists: Boolean)

    fun candidates(nativeLibraryDir: String?, customPath: String?): List<Candidate> {
        val out = ArrayList<Candidate>(3)
        if (!customPath.isNullOrBlank()) {
            val file = File(customPath)
            out.add(Candidate("自定义路径", file, isExecutable(file)))
        }
        if (!nativeLibraryDir.isNullOrBlank()) {
            val dir = File(nativeLibraryDir)
            for (name in listOf(PRIMARY_NAME, FALLBACK_NAME)) {
                val file = File(dir, name)
                out.add(Candidate("APK 内置", file, isExecutable(file)))
            }
        }
        return out
    }

    /** 返回第一个可用（存在且可执行）的 qemu 文件。 */
    fun locate(nativeLibraryDir: String?, customPath: String?): File? =
        candidates(nativeLibraryDir, customPath).firstOrNull { it.exists }?.file

    private fun isExecutable(file: File): Boolean = file.isFile && file.canExecute()

    /**
     * 跑一次 `--version` 读版本行（用于设置页自检）。
     * 在后台线程调用；超时 5 秒，失败返回 null。
     */
    fun version(executable: File): String? = try {
        val process = ProcessBuilder(executable.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!finished) process.destroyForcibly()
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    } catch (t: Throwable) {
        null
    }

    /**
     * qemu 的 -L 数据目录（需要 keymaps/en-us）。优先 APK assets 解出来的
     * `filesDir/qemu/pc-bios`，其次 nativeLibraryDir 旁边的 share/qemu。
     */
    fun keymapsDir(context: Context, executable: File): File? {
        val assetDir = File(File(context.filesDir, "qemu"), "pc-bios")
        if (File(assetDir, "keymaps/en-us").isFile) return assetDir
        val libDir = executable.parentFile ?: return null
        for (candidate in listOf(
            File(libDir, "share/qemu"),
            File(libDir, "pc-bios"),
            File(libDir.parentFile, "share/qemu"),
        )) {
            if (File(candidate, "keymaps/en-us").isFile) return candidate
        }
        return null
    }
}
