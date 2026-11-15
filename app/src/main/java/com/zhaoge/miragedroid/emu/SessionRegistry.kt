package com.zhaoge.miragedroid.emu

import android.content.Context
import com.zhaoge.miragedroid.data.DeviceStore

/**
 * App 级别的会话表：同一台设备只有一个 SessionManager，切页面/旋转屏幕不重连，
 * QEMU 进程也不随 Activity 销毁而退出（回到设备库再进来还是同一会话）。
 */
class SessionRegistry(
    private val context: Context,
    private val store: DeviceStore,
) {
    private val sessions = LinkedHashMap<String, SessionManager>()

    @Synchronized
    fun get(deviceId: String): SessionManager? {
        if (store.device(deviceId) == null) return null
        return sessions.getOrPut(deviceId) { SessionManager(context, store, deviceId) }
    }

    @Synchronized
    fun peek(deviceId: String): SessionManager? = sessions[deviceId]

    @Synchronized
    fun all(): List<SessionManager> = sessions.values.toList()

    @Synchronized
    fun remove(deviceId: String) {
        val session = sessions.remove(deviceId) ?: return
        session.shutdown()
    }

    @Synchronized
    fun stopAll() {
        sessions.values.forEach { it.shutdown() }
        sessions.clear()
    }

    fun anyActive(): Boolean = all().any { it.isActive() }

    /** 有没有正在运行的模拟核心（资源占用提示用）。 */
    fun activeDevices(): List<SessionManager> = all().filter { it.isActive() }
}
