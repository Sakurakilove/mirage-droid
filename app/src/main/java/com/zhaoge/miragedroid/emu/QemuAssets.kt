package com.zhaoge.miragedroid.emu

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 把 APK assets/qemu/ 下的 qemu 数据文件（pc-bios、keymaps 等）解压到
 * filesDir/qemu/，供 `-L` 参数使用。assets 里没有内容时返回 null（qemu 仍可跑，
 * 只是缺 keymap 时 VNC 可能报警告）。
 */
object QemuAssets {
    private const val ASSET_ROOT = "qemu"

    /** 解压（覆盖）assets/qemu 并返回 -L 应传的目录；没有 assets 时返回 null。 */
    fun ensure(context: Context): File? = try {
        val top = context.assets.list(ASSET_ROOT) ?: emptyArray()
        if (top.isEmpty()) {
            null
        } else {
            val target = File(context.filesDir, ASSET_ROOT)
            copyDirectory(context, ASSET_ROOT, target)
            val pcBios = File(target, "pc-bios")
            if (pcBios.isDirectory) pcBios else target
        }
    } catch (t: Throwable) {
        null
    }

    private fun copyDirectory(context: Context, assetPath: String, target: File) {
        val children = try {
            context.assets.list(assetPath)
        } catch (error: java.io.IOException) {
            null
        } ?: emptyArray()
        if (children.isNotEmpty()) {
            target.mkdirs()
            for (child in children) {
                copyDirectory(context, "$assetPath/$child", File(target, child))
            }
            return
        }
        // 空目录与文件都表现为空列表：先按文件打开，失败就当空目录建出来
        try {
            copyFile(context, assetPath, target)
        } catch (error: java.io.IOException) {
            target.mkdirs()
        }
    }

    private fun copyFile(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 128 * 1024)
            }
        }
    }
}
