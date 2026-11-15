package com.zhaoge.miragedroid.emu

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 找到并准备好可执行的 qemu-system-arm。
 *
 * Android 上的约定：qemu 主程序被改名成 `libqemu-system-arm.so` 放进 jniLibs，
 * 安装后位于 applicationInfo.nativeLibraryDir（APK 解压出来的目录）。
 * 该目录里的文件是否能 exec 取决于安装方式与系统策略，所以这里会：
 *   1) 先尝试 `chmod 755`（File.setExecutable，失败不抛异常）；
 *   2) 再检查 canExecute()，把失败原因整理成可读的诊断文本给界面显示。
 */
object QemuLocator {
    /** jniLibs 里的名字：必须 lib*.so 结尾，Android 才会解压到 nativeLibraryDir。 */
    const val PRIMARY_NAME = "libqemu-system-arm.so"

    /** 兼容直接放 qemu-system-arm 的构建（如测试版 APK）。 */
    const val FALLBACK_NAME = "qemu-system-arm"

    data class Candidate(
        val label: String,
        val file: File,
        val exists: Boolean,
        val executable: Boolean,
    )

    /** qemu 的定位结果：文件、是否可执行、诊断文本。 */
    data class Location(
        val file: File?,
        val executable: Boolean,
        val detail: String,
    )

    data class VersionInfo(val version: String?, val error: String?)

    fun candidates(nativeLibraryDir: String?, customPath: String?): List<Candidate> {
        val out = ArrayList<Candidate>(3)
        if (!customPath.isNullOrBlank()) {
            val file = File(customPath)
            out.add(Candidate("自定义路径", file, file.isFile, file.isFile && file.canExecute()))
        }
        if (!nativeLibraryDir.isNullOrBlank()) {
            val dir = File(nativeLibraryDir)
            for (name in listOf(PRIMARY_NAME, FALLBACK_NAME)) {
                val file = File(dir, name)
                out.add(Candidate("APK 内置", file, file.isFile, file.isFile && file.canExecute()))
            }
        }
        return out
    }

    /**
     * 解析可用的 qemu：会顺手尝试 chmod +x。
     * 找不到文件 / 文件无执行权限时都返回 [Location]，由调用方决定怎么提示。
     */
    fun resolve(nativeLibraryDir: String?, customPath: String?): Location {
        val all = candidates(nativeLibraryDir, customPath)
        val existing = all.filter { it.exists }
        if (existing.isEmpty()) {
            val listing = all.joinToString("\n") { "· ${it.label}：${it.file.absolutePath}（不存在）" }
            return Location(
                file = null,
                executable = false,
                detail = "当前 APK 没有可用的 qemu 核心。已检查：\n$listing",
            )
        }
        for (candidate in existing) {
            if (ensureExecutable(candidate.file)) {
                return Location(
                    file = candidate.file,
                    executable = true,
                    detail = "使用${candidate.label}核心：${candidate.file.absolutePath}",
                )
            }
        }
        val first = existing.first()
        val listing = existing.joinToString("\n") { "· ${it.file.absolutePath}（可执行=${it.file.canExecute()}）" }
        return Location(
            file = first.file,
            executable = false,
            detail = "找到模拟核心但无法执行（chmod +x 失败）：\n$listing\n" +
                "请确认 APK 把 qemu 放在 jniLibs/nativeLibraryDir 且允许解压原生库。",
        )
    }

    /** 兼容旧调用：返回第一个存在的候选（已尝试 chmod，不代表一定能执行）。 */
    fun locate(nativeLibraryDir: String?, customPath: String?): File? =
        resolve(nativeLibraryDir, customPath).file

    /** 把文件变成可执行：先看现有权限，再尝试 chmod 755（owner/other 两种都试）。 */
    fun ensureExecutable(file: File): Boolean {
        if (!file.isFile) return false
        if (file.canExecute()) return true
        try {
            file.setExecutable(true, false)
        } catch (_: Throwable) {
        }
        if (file.canExecute()) return true
        try {
            file.setExecutable(true, true)
        } catch (_: Throwable) {
        }
        return file.canExecute()
    }

    /**
     * 跑一次 `--version` 做真机自检：带上 LD_LIBRARY_PATH（qemu 依赖的 .so 与主程序同目录），
     * 成功返回版本行；失败返回可读原因（含输出尾巴）。
     */
    fun versionDetailed(executable: File, workDir: File? = null): VersionInfo {
        if (!ensureExecutable(executable)) {
            return VersionInfo(null, "不可执行：${executable.absolutePath}（chmod +x 失败）")
        }
        return try {
            val builder = ProcessBuilder(executable.absolutePath, "--version")
            builder.redirectErrorStream(true)
            if (workDir != null) builder.directory(workDir)
            builder.environment()["LD_LIBRARY_PATH"] = executable.parentFile?.absolutePath ?: ""
            builder.environment()["TMPDIR"] = workDir?.absolutePath ?: System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            val exit = if (finished) {
                try {
                    process.exitValue()
                } catch (error: IllegalThreadStateException) {
                    -1
                }
            } else {
                process.destroyForcibly()
                -1
            }
            val text = output.trim()
            if (text.isEmpty()) {
                VersionInfo(null, "qemu --version 无输出（退出码 $exit）")
            } else {
                val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                val error = if (finished && exit != 0) tail(text, 20) else null
                VersionInfo(firstLine, error)
            }
        } catch (error: Throwable) {
            val hint = if (isPermissionDenied(error)) {
                "\n提示：系统拒绝从该路径执行二进制（EACCES）。"
            } else {
                ""
            }
            VersionInfo(null, "启动 qemu --version 失败：${error.message ?: error.javaClass.simpleName}$hint")
        }
    }

    /** 兼容旧调用。 */
    fun version(executable: File): String? = versionDetailed(executable).version

    /** 取文本末尾 [maxLines] 行（UI 里显示 stderr 尾巴用）。 */
    fun tail(text: String, maxLines: Int = 20): String {
        if (text.isBlank()) return ""
        val lines = text.trimEnd().lineSequence().toList()
        return lines.takeLast(maxLines).joinToString("\n")
    }

    fun isPermissionDenied(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("Permission denied", ignoreCase = true) ||
            message.contains("error=13", ignoreCase = true) ||
            message.contains("EACCES", ignoreCase = true)
    }

    /**
     * qemu 的 -L 数据目录（需要 keymaps/en-us）。优先 APK assets 解出来的
     * `filesDir/qemu/pc-bios`，其次 nativeLibraryDir 旁边的 share/qemu。
     */
    fun keymapsDir(context: Context, executable: File): File? {
        // 先从 APK assets 解压 qemu 数据（keymaps 约 30 MB）；解压后的
        // filesDir/qemu/pc-bios 就是 -L 需要的数据目录。缺了它 QEMU 会以
        // "could not find keymap file for language 'en-us'" 直接退出。
        QemuAssets.ensure(context)?.let { dir ->
            if (File(dir, "keymaps/en-us").isFile) return dir
        }
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
