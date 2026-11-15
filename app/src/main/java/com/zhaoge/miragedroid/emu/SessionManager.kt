package com.zhaoge.miragedroid.emu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zhaoge.miragedroid.data.DeviceProfile
import com.zhaoge.miragedroid.data.DeviceProfiles
import com.zhaoge.miragedroid.data.DeviceStore
import com.zhaoge.miragedroid.data.FirmwareRecord
import com.zhaoge.miragedroid.data.JsonValue
import com.zhaoge.miragedroid.data.SessionState
import com.zhaoge.miragedroid.data.VirtualDevice
import com.zhaoge.miragedroid.net.NshClient
import com.zhaoge.miragedroid.net.QmpClient
import com.zhaoge.miragedroid.net.QmpCommands
import com.zhaoge.miragedroid.net.RfbFrame
import com.zhaoge.miragedroid.net.VncClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 会话层错误（启动前的可读失败原因）。 */
class SessionException(message: String) : Exception(message)

/**
 * 一台设备的 QEMU 会话：进程生命周期 + QMP/VNC/控制台连接 + 输入注入。
 *
 * 线程模型：
 * - 启动/停止/命令注入都在单线程 [worker] 上串行执行（QEMU 启停很重，不能并发）；
 * - 读循环在 net 层自己的线程里；
 * - 所有 [SessionListener] 回调都 post 回主线程，界面层无需再切线程。
 */
class SessionManager(
    private val context: Context,
    private val store: DeviceStore,
    val deviceId: String,
) {
    private val paths = DevicePaths(store, deviceId, context.cacheDir)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val stateLock = Any()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MirageSession-$deviceId").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var qmp: QmpClient? = null
    private var vnc: VncClient? = null
    private var nsh: NshClient? = null
    private var screenThread: Thread? = null
    private var consoleThread: Thread? = null

    /** 手指状态与上一次移动时间：触摸移动节流到 ~90Hz，避免刷爆固件触摸队列。 */
    @Volatile
    private var fingerDown = false

    @Volatile
    private var lastTouchMoveAt = 0L

    @Volatile
    var state: SessionState = SessionState.STOPPED
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var pid: Long = -1
        private set

    @Volatile
    var startedAt: Long = 0
        private set

    @Volatile
    var plan: LaunchPlan? = null
        private set

    @Volatile
    var latestFrame: RfbFrame? = null
        private set

    @Volatile
    var bootedAt: Long = 0
        private set

    // ------------------------------------------------------------ 观察者

    fun addListener(listener: SessionListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    private fun notifyState() {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        mainHandler.post { snapshot.forEach { it.onSessionStateChanged(this) } }
    }

    private fun notifyFrame(frame: RfbFrame) {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        mainHandler.post { snapshot.forEach { it.onSessionFrame(this, frame) } }
    }

    private fun notifyConsole(text: String) {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        mainHandler.post { snapshot.forEach { it.onSessionConsole(this, text) } }
    }

    // ------------------------------------------------------------ 设备信息

    fun device(): VirtualDevice? = store.device(deviceId)

    fun profile(): DeviceProfile {
        val device = device()
        return device?.profile() ?: DeviceProfiles.fallback(deviceId)
    }

    fun firmware(): FirmwareRecord? = device()?.firmwareId?.let { store.firmware(it) }

    fun isActive(): Boolean =
        state == SessionState.LAUNCHING || state == SessionState.RUNNING || state == SessionState.STOPPING

    fun qemuCommandLine(): String? = plan?.commandLine()

    fun logs(): List<File> = paths.allLogs()

    fun consoleHistory(): String = nsh?.historyText() ?: ""

    // ------------------------------------------------------------ 启动 / 停止

    fun start() {
        synchronized(stateLock) {
            if (isActive()) return
            state = SessionState.LAUNCHING
            lastError = null
            exitCode = null
        }
        notifyState()
        worker.execute { doStart() }
    }

    fun stop() {
        synchronized(stateLock) {
            if (state == SessionState.STOPPED || state == SessionState.STOPPING) return
            state = SessionState.STOPPING
        }
        notifyState()
        worker.execute { doStop() }
    }

    fun restart() {
        synchronized(stateLock) {
            if (state == SessionState.LAUNCHING || state == SessionState.STOPPING) return
            state = SessionState.STOPPING
        }
        notifyState()
        worker.execute {
            doStop()
            synchronized(stateLock) {
                state = SessionState.LAUNCHING
                lastError = null
                exitCode = null
            }
            notifyState()
            doStart()
        }
    }

    private fun doStart() {
        try {
            val device = store.device(deviceId) ?: throw SessionException("设备不存在（可能已被删除）")
            val profile = device.profile()
            if (DeviceProfiles.byId(profile.id) == null) {
                throw SessionException("机型 ${profile.id} 暂不支持启动。")
            }
            val firmware = device.firmwareId?.let { store.firmware(it) }
                ?: throw SessionException("该设备还没有关联固件。请先导入官方 OTA 包。")
            val firmwareDir = store.firmwareDir(firmware.id)
            val ap = File(firmwareDir, firmware.apFile)
            if (!ap.isFile) {
                throw SessionException("固件文件缺失：${ap.absolutePath}，请重新导入固件。")
            }
            val missing = paths.missingImages(profile)
            if (missing.isNotEmpty()) {
                throw SessionException(
                    "设备存储尚未构建（缺少 ${missing.joinToString("、")}）。请重新导入固件并生成设备存储。",
                )
            }

            val executable = QemuLocator.locate(
                context.applicationInfo.nativeLibraryDir,
                store.settings().qemuPath,
            ) ?: throw SessionException(
                "未检测到模拟核心（libqemu-system-arm.so）。当前 APK 不含 qemu 核心，" +
                    "请安装含模拟核心的构建，或在设置里指定 qemu 可执行文件。",
            )

            paths.prepare()
            val keymaps = QemuLocator.keymapsDir(context, executable)
            val launchPlan = LaunchPlanBuilder.build(
                profile = profile,
                device = device,
                paths = paths,
                firmwareDir = firmwareDir,
                executable = executable.absolutePath,
                keymapsDir = keymaps,
            )
            plan = launchPlan

            val builder = ProcessBuilder(launchPlan.command())
            builder.directory(launchPlan.workingDirectory)
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.stdoutLog))
            builder.environment().apply {
                put("TMPDIR", context.cacheDir.absolutePath)
                put("HOME", context.filesDir.absolutePath)
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir ?: "")
                put("QEMU_AUDIO_DRV", "none")
            }

            val started = builder.start()
            process = started
            pid = processPid(started)
            startedAt = System.currentTimeMillis()
            if (pid > 0) {
                try {
                    paths.pidFile.writeText(pid.toString())
                } catch (_: IOException) {
                }
            }
            startExitWatcher(started)
            store.upsertDevice(device.copy(lastBootedAt = startedAt))

            val client = connectQmpWithRetry(started)
            qmp = client
            client.setListener(object : QmpClient.Listener {
                override fun onEvent(name: String, data: JsonValue?) {
                    if (name == "SHUTDOWN" || name == "POWERDOWN" || name == "RESET") {
                        notifyState()
                    }
                }

                override fun onClosed(error: Throwable?) {
                    // 进程退出由 exit watcher 统一处理，这里只刷新界面
                    notifyState()
                }
            })
            // -S（调试模式）下 qemu 启动即暂停，由界面上的"继续运行"发 cont
            try {
                client.setBattery(device.batteryLevel, device.batteryCharging)
            } catch (_: IOException) {
            }

            synchronized(stateLock) {
                state = SessionState.RUNNING
                bootedAt = System.currentTimeMillis()
            }
            notifyState()

            // 显示与控制台异步挂接：失败不致命，界面会显示"等待画面"
            screenThread = Thread { attachScreen() }.apply {
                name = "MirageScreen-$deviceId"
                isDaemon = true
                start()
            }
            consoleThread = Thread { attachConsole() }.apply {
                name = "MirageConsole-$deviceId"
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            lastError = (error.message ?: error.javaClass.simpleName) + stdoutTail()
            cleanupProcess()
            synchronized(stateLock) { state = SessionState.ERROR }
            notifyState()
        }
    }

    private fun doStop() {
        qmp?.let { client ->
            try {
                client.quit()
            } catch (_: Throwable) {
            }
        }
        closeClients()
        val running = process
        if (running != null) {
            try {
                if (!running.waitFor(6, TimeUnit.SECONDS)) {
                    running.destroy()
                    if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        cleanupProcess()
        synchronized(stateLock) { state = SessionState.STOPPED }
        notifyState()
    }

    private fun cleanupProcess() {
        process = null
        pid = -1
        startedAt = 0
        for (socket in listOf(paths.qmpSocket, paths.qmpAuxSocket, paths.vncSocket, paths.consoleSocket, paths.gdbSocket)) {
            try {
                socket.delete()
            } catch (_: Throwable) {
            }
        }
        try {
            paths.pidFile.delete()
        } catch (_: Throwable) {
        }
    }

    private fun closeClients() {
        vnc?.close()
        qmp?.close()
        nsh?.close()
        vnc = null
        qmp = null
        nsh = null
    }

    private fun startExitWatcher(instance: Process) {
        Thread {
            val code = try {
                instance.waitFor()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
            onProcessExit(instance, code)
        }.apply {
            name = "MirageExit-$deviceId"
            isDaemon = true
            start()
        }
    }

    private fun onProcessExit(instance: Process, code: Int) {
        if (process !== instance) return
        exitCode = code
        synchronized(stateLock) {
            // 正常 stop 时状态已是 STOPPING/STOPPED，由 doStop() 收尾
            if (state != SessionState.STOPPING && state != SessionState.STOPPED) {
                state = SessionState.ERROR
                lastError = "模拟核心已退出（退出码 $code）" + stdoutTail()
            }
        }
        closeClients()
        process = null
        pid = -1
        startedAt = 0
        notifyState()
    }

    private fun connectQmpWithRetry(instance: Process): QmpClient {
        val deadline = System.currentTimeMillis() + 30_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (!instance.isAlive) {
                throw SessionException("qemu 在启动过程中退出（退出码 ${instance.exitValue()}）" + stdoutTail())
            }
            try {
                return QmpClient.connect(paths.qmpSocket.absolutePath, 400)
            } catch (error: Throwable) {
                lastError = error
            }
            try {
                Thread.sleep(120)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        throw SessionException("qemu 未在 30 秒内打开 QMP 监视器（${lastError?.message ?: "超时"}）")
    }

    /** API 30+ 才有 Process.pid()；低版本用反射，拿不到就返回 -1（仅展示用）。 */
    private fun processPid(instance: Process): Long = try {
        val method = Process::class.java.getMethod("pid")
        (method.invoke(instance) as? Long) ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    private fun stdoutTail(): String {
        val file = paths.stdoutLog
        if (!file.isFile) return ""
        return try {
            val text = file.readText(Charsets.UTF_8)
            val tail = text.takeLast(1600).trim()
            if (tail.isEmpty()) "" else "\n--- qemu 输出（末尾） ---\n$tail"
        } catch (error: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------ 显示 / 控制台

    private fun attachScreen() {
        try {
            val client = VncClient.connect(paths.vncSocket.absolutePath, 20_000)
            client.setListener(object : VncClient.Listener {
                override fun onFrame(frame: RfbFrame) {
                    latestFrame = frame
                    notifyFrame(frame)
                }

                override fun onResized(width: Int, height: Int) {
                    notifyState()
                }

                override fun onClosed(error: Throwable?) {
                    if (error != null && state == SessionState.RUNNING) {
                        lastError = "屏幕连接断开：${error.message}"
                    }
                    notifyState()
                }
            })
            vnc = client
            notifyState()
        } catch (error: Throwable) {
            if (state == SessionState.RUNNING) {
                lastError = "屏幕连接失败：${error.message ?: error.javaClass.simpleName}"
            }
            notifyState()
        }
    }

    private fun attachConsole() {
        try {
            val client = NshClient.connect(paths.consoleSocket.absolutePath, 15_000)
            client.setListener(object : NshClient.Listener {
                override fun onData(data: ByteArray) {
                    notifyConsole(client.historyText())
                }

                override fun onClosed(error: Throwable?) {
                    notifyState()
                }
            })
            nsh = client
            client.start()
            notifyState()
        } catch (error: Throwable) {
            // 控制台是可选能力（部分机型/构建没有 NSH），失败不改变会话状态
        }
    }

    /** 主动重连屏幕（例如界面恢复时还没连上）。 */
    fun retryScreen() {
        if (state != SessionState.RUNNING || vnc != null) return
        screenThread = Thread { attachScreen() }.apply {
            name = "MirageScreenRetry-$deviceId"
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------ 输入

    /** 触摸：坐标是 VNC 面板像素。 */
    fun touch(x: Int, y: Int, pressed: Boolean) {
        vnc?.pointer(x, y, pressed)
    }

    fun touchRatio(xRatio: Float, yRatio: Float, pressed: Boolean) {
        val client = vnc ?: return
        val now = System.currentTimeMillis()
        if (pressed) {
            if (!fingerDown) {
                // 按下必须立刻发出去
                fingerDown = true
                lastTouchMoveAt = now
                client.pointerRatio(xRatio, yRatio, true)
                return
            }
            if (now - lastTouchMoveAt < TOUCH_MOVE_INTERVAL_MS) return
            lastTouchMoveAt = now
            client.pointerRatio(xRatio, yRatio, true)
        } else {
            fingerDown = false
            lastTouchMoveAt = 0L
            client.pointerRatio(xRatio, yRatio, false)
        }
    }

    fun key(qcode: String, down: Boolean) {
        worker.execute {
            try {
                qmp?.key(qcode, down)
            } catch (_: IOException) {
            }
        }
    }

    /** 暂停虚拟机（QMP stop）。 */
    fun pause() {
        worker.execute {
            try {
                qmp?.execute(QmpCommands.stopMachine())
            } catch (_: IOException) {
                // 忽略：暂停是调试辅助功能
            }
        }
    }

    /** 继续运行（QMP cont），用于 -S 调试启动后释放。 */
    fun resume() {
        worker.execute {
            try {
                qmp?.execute(QmpCommands.contMachine())
            } catch (_: IOException) {
                // 忽略
            }
        }
    }

    fun keyPress(qcode: String) {
        worker.execute {
            try {
                qmp?.key(qcode, true)
                Thread.sleep(60)
                qmp?.key(qcode, false)
            } catch (_: IOException) {
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun applyBattery(level: Int, charging: Boolean) {
        worker.execute {
            try {
                qmp?.setBattery(level, charging)
            } catch (_: IOException) {
            }
            val device = store.device(deviceId)
            if (device != null) {
                store.upsertDevice(device.copy(batteryLevel = level.coerceIn(0, 100), batteryCharging = charging))
                notifyState()
            }
        }
    }

    // ------------------------------------------------------------ 截图

    @Volatile
    private var screenshotting = false

    /** QEMU screendump 出 PNG（面板原生分辨率）。回调在主线程。 */
    fun screenshot(target: File, onDone: (Boolean, String?) -> Unit) {
        if (screenshotting) {
            onDone(false, "正在截图中，请稍候")
            return
        }
        screenshotting = true
        worker.execute {
            var ok = false
            var message: String? = null
            try {
                val client = qmp ?: throw IOException("QMP 未连接")
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                client.screendump(target.absolutePath)
                ok = target.isFile && target.length() > 0
                if (!ok) message = "截图文件为空"
            } catch (error: Throwable) {
                message = error.message ?: error.javaClass.simpleName
            } finally {
                screenshotting = false
                mainHandler.post { onDone(ok, message) }
            }
        }
    }

    // ------------------------------------------------------------ 控制台输入

    fun consoleSend(line: String) {
        nsh?.sendLine(line)
    }

    fun consoleSendRaw(text: String) {
        nsh?.send(text)
    }

    /** 只清空本地回滚缓冲，不影响设备。 */
    fun consoleClearLocal() {
        nsh?.clearHistory()
    }

    // ------------------------------------------------------------ 清理

    /** 停会话并释放线程池（删设备/退出时调用）。等 doStop 跑完再关线程池。 */
    fun shutdown() {
        if (isActive()) stop()
        closeClients()
        try {
            worker.execute { worker.shutdown() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            worker.shutdownNow()
        }
    }

    companion object {
        /** 触摸移动最小间隔（毫秒），对应 Mirage ScreenFeed 的 ~90Hz。 */
        private const val TOUCH_MOVE_INTERVAL_MS = 11L

        /** 会话在该目录下缓存 unix socket（cacheDir 可随时被系统清理）。 */
        fun runtimeRoot(context: Context): File = File(context.cacheDir, "runtime")
    }
}
