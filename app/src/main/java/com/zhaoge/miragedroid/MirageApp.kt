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

    /** 当前 APK 是否带可执行的 qemu 核心。 */
    fun qemuExecutable(): File? = QemuLocator.locate(
        applicationInfo.nativeLibraryDir,
        store.settings().qemuPath,
    )

    fun qemuAvailable(): Boolean = qemuExecutable() != null

    /** devicelib（导入固件/生成镜像）是否可用。 */
    fun deviceLibAvailable(): Boolean = com.zhaoge.miragedroid.data.NativeCore.available()

    companion object {
        @Volatile
        lateinit var instance: MirageApp
            private set

        fun of(context: Context): MirageApp = context.applicationContext as MirageApp
    }
}
