package com.zhaoge.miragedroid

import android.app.Application
import android.content.Context
import com.zhaoge.miragedroid.data.DeviceStore
import com.zhaoge.miragedroid.data.FirmwareImporter
import com.zhaoge.miragedroid.emu.QemuLocator
import com.zhaoge.miragedroid.emu.SessionRegistry
import java.io.File

/**
 * 进程级单例：数据层（DeviceStore）、会话表、固件导入器。
 *
 * APK 里没有 native 库时这里不做任何猜测——[qemuAvailable] 返回 false，
 * 界面显示"请安装含模拟核心的构建"。
 */
class MirageApp : Application() {
    lateinit var store: DeviceStore
        private set

    lateinit var sessions: SessionRegistry
        private set

    lateinit var importer: FirmwareImporter
        private set

    /** qemu --version 结果缓存（设置页显示）。 */
    @Volatile
    var qemuVersion: String? = null

    @Volatile
    var qemuVersionChecked = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = DeviceStore(File(filesDir, "library"))
        store.ensureDirectories()
        sessions = SessionRegistry(this, store)
        importer = FirmwareImporter(this, store)
    }

    /** 每次重新解析（会顺手 chmod +x）：设置页/设备页都要最新的真实状态。 */
    fun qemuLocation(): QemuLocator.Location = QemuLocator.resolve(
        applicationInfo.nativeLibraryDir,
        store.settings().qemuPath,
    )

    /** 当前 APK 是否带可执行的 qemu 核心。 */
    fun qemuExecutable(): File? = qemuLocation().file

    fun qemuAvailable(): Boolean = qemuLocation().executable

    /** 核心诊断文本（不存在 / 无执行权限 / 路径），给界面显示。 */
    fun qemuStatusDetail(): String = qemuLocation().detail

    /** devicelib（导入固件/生成镜像）是否可用。 */
    fun deviceLibAvailable(): Boolean = com.zhaoge.miragedroid.data.NativeCore.available()

    companion object {
        @Volatile
        lateinit var instance: MirageApp
            private set

        fun of(context: Context): MirageApp = context.applicationContext as MirageApp
    }
}
