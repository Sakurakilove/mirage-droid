package com.zhaoge.miragedroid.net

import com.zhaoge.miragedroid.data.JsonValue
import com.zhaoge.miragedroid.data.jn
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** QMP 返回 error 时抛出。 */
class QmpException(val error: QmpError) : IOException(error.toString())

/**
 * QEMU Machine Protocol 客户端（JSON 行协议，unix socket）。
 *
 * 同步 API + 单读线程：[execute] 发送命令并阻塞等对应 id 的响应，界面层放在
 * 后台线程调用即可。事件（SHUTDOWN / RESET / STOP ...）通过 [Listener] 回调，
 * 回调在读线程上执行。
 */
class QmpClient private constructor(private val connection: UnixSocketConnection) : Closeable {
    interface Listener {
        fun onEvent(name: String, data: JsonValue?)

        fun onClosed(error: Throwable?)
    }

    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var response: QmpMessage.Response? = null
    }

    private val lock = Any()
    private val pending = LinkedHashMap<Long, Pending>()
    private val lineReader = QmpLineReader()
    private val pendingLines = ArrayDeque<String>()
    private val readBuffer = ByteArray(16 * 1024)
    private var nextId = 1L

    @Volatile
    private var closed = false

    @Volatile
    private var listener: Listener? = null

    private var readerThread: Thread? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    // ------------------------------------------------------------ 连接

    private fun readGreeting(): QmpMessage.Greeting {
        while (true) {
            val read = connection.input.read(readBuffer)
            if (read < 0) throw IOException("QMP 连接在问候阶段被关闭")
            if (read == 0) continue
            pendingLines.addAll(lineReader.feed(readBuffer, 0, read))
            while (!pendingLines.isEmpty()) {
                val message = QmpProtocol.parseLine(pendingLines.removeFirst())
                if (message is QmpMessage.Greeting) return message
                throw IOException("QMP 问候格式不正确：$message")
            }
        }
    }

    private fun startReader() {
        val thread = Thread {
            var failure: Throwable? = null
            try {
                // 握手阶段可能已经把后续行读进 pendingLines
                while (!pendingLines.isEmpty()) dispatchLine(pendingLines.removeFirst())
                while (!closed) {
                    val read = connection.input.read(readBuffer)
                    if (read < 0) throw IOException("QMP 连接已关闭")
                    if (read == 0) continue
                    for (line in lineReader.feed(readBuffer, 0, read)) dispatchLine(line)
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                val wasClosed = closed
                closed = true
                val waiting: List<Pending>
                synchronized(lock) {
                    waiting = pending.values.toList()
                    pending.clear()
                }
                for (slot in waiting) {
                    slot.response = null
                    slot.latch.countDown()
                }
                try {
                    connection.close()
                } catch (_: IOException) {
                }
                if (!wasClosed) listener?.onClosed(failure)
            }
        }
        thread.name = "QmpReader"
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun dispatchLine(line: String) {
        when (val message = QmpProtocol.parseLine(line)) {
            is QmpMessage.Response -> {
                val id = (message.id as? JsonValue.Num)?.value?.toLong() ?: return
                val slot: Pending?
                synchronized(lock) { slot = pending[id] }
                if (slot != null) {
                    slot.response = message
                    slot.latch.countDown()
                }
            }
            is QmpMessage.Event -> listener?.onEvent(message.name, message.data)
            else -> {
                // 问候重发/无法识别的行：忽略，不要打断读循环
            }
        }
    }

    // ------------------------------------------------------------ 命令

    @Throws(IOException::class)
    fun execute(command: QmpCommand, timeoutMs: Long = 8000L): QmpMessage.Response {
        val id: Long
        val slot: Pending
        synchronized(lock) {
            if (closed) throw IOException("QMP 连接已关闭")
            id = nextId++
            slot = Pending()
            pending[id] = slot
        }
        try {
            synchronized(lock) {
                connection.write(QmpProtocol.encodeLine(command, id))
            }
            if (!slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("QMP 命令超时（${timeoutMs}ms）：${command.name}")
            }
            val response = slot.response ?: throw IOException("QMP 连接已关闭")
            response.error?.let { throw QmpException(it) }
            return response
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    /** qmp_capabilities 之后才能用其它命令。 */
    fun capabilities() {
        execute(QmpCommands.capabilities())
    }

    fun queryStatus(): String {
        val response = execute(QmpCommands.queryStatus())
        return (response.result as? JsonValue.Obj)?.let { obj ->
            when (val status = obj.fields["status"]) {
                is JsonValue.Str -> status.value
                else -> "unknown"
            }
        } ?: "unknown"
    }

    fun isRunning(): Boolean = queryStatus() == "running"

    fun key(qcode: String, down: Boolean) {
        execute(QmpCommands.inputKey(qcode, down))
    }

    fun touch(xRatio: Double, yRatio: Double, down: Boolean?) {
        execute(QmpCommands.inputTouch(xRatio, yRatio, down))
    }

    fun qomGet(path: String, property: String): JsonValue? =
        execute(QmpCommands.qomGet(path, property)).result

    fun qomSet(path: String, property: String, value: JsonValue) {
        execute(QmpCommands.qomSet(path, property, value))
    }

    /** 读取模拟电量：返回 (电量 0..100, 是否充电)。 */
    fun battery(): Pair<Int, Boolean>? = try {
        val level = (qomGet("/machine", "battery-level") as? JsonValue.Num)?.value?.toInt() ?: return null
        val charging = (qomGet("/machine", "battery-charging") as? JsonValue.Bool)?.value ?: false
        level.coerceIn(0, 100) to charging
    } catch (error: IOException) {
        null
    }

    fun setBattery(level: Int, charging: Boolean) {
        qomSet("/machine", "battery-level", jn(level.coerceIn(0, 100)))
        qomSet("/machine", "battery-charging", JsonValue.Bool(charging))
    }

    fun screendump(to: String) {
        execute(QmpCommands.screendump(to), timeoutMs = 15000L)
    }

    fun hmp(commandLine: String): String {
        val response = execute(QmpCommands.humanMonitor(commandLine))
        return (response.result as? JsonValue.Str)?.value ?: ""
    }

    /** 关机：QEMU 收到 quit 会直接退出，可能来不及回包，所以忽略错误。 */
    fun quit() {
        val id: Long
        val slot: Pending
        synchronized(lock) {
            if (closed) return
            id = nextId++
            slot = Pending()
            pending[id] = slot
        }
        try {
            synchronized(lock) {
                connection.write(QmpProtocol.encodeLine(QmpCommands.quit(), id))
            }
            slot.latch.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: IOException) {
        }
        readerThread?.interrupt()
    }

    companion object {
        /** 连接 QMP 并完成 capabilities 协商；对端不是 QMP 或超时抛 IOException。 */
        @Throws(IOException::class)
        fun connect(path: String, timeoutMs: Int = 5000): QmpClient {
            val connection = UnixSocketConnection.connect(path, timeoutMs)
            val client = QmpClient(connection)
            try {
                client.readGreeting()
                // 先起读线程再发 qmp_capabilities，否则响应没人收
                client.startReader()
                client.capabilities()
            } catch (error: Throwable) {
                client.close()
                if (error is IOException) throw error
                throw IOException("QMP 握手失败：${error.message}", error)
            }
            client.startReader()
            return client
        }
    }
}
