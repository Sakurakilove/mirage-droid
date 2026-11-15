package com.zhaoge.miragedroid.net

import java.io.Closeable
import java.io.IOException

/**
 * 控制台客户端：QEMU 把 console UART 接到一个 unix chardev socket
 * （`-chardev socket,id=con,...` + `-serial chardev:con`），连上它就能读启动日志、
 * 从 NSH 提示符里敲命令。
 *
 * NSH 本身只有一行行 ASCII 命令 + 文本输出，客户端只做原始字节转发与回滚缓冲；
 * 解析（去 ANSI / 找提示符）在 [NshOutput] 里。
 */
class NshClient private constructor(private val connection: UnixSocketConnection) : Closeable {
    interface Listener {
        /** 控制台新数据（原始字节，可能是半行/半个 UTF-8 字符）。 */
        fun onData(data: ByteArray)

        fun onClosed(error: Throwable?)
    }

    /** 控制台回滚（默认 2 MiB，足够装下整段启动日志）。 */
    val history = ByteRing(2 * 1024 * 1024)

    private val writeLock = Any()
    private val readBuffer = ByteArray(16 * 1024)

    @Volatile
    private var closed = false

    @Volatile
    private var listener: Listener? = null

    private var readerThread: Thread? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.let { current ->
            if (history.size > 0) current.onData(history.snapshot())
        }
    }

    fun start() {
        if (readerThread != null) return
        val thread = Thread {
            var failure: Throwable? = null
            try {
                while (!closed) {
                    val read = connection.input.read(readBuffer)
                    if (read < 0) throw IOException("控制台连接已关闭")
                    if (read == 0) continue
                    val chunk = readBuffer.copyOfRange(0, read)
                    history.append(chunk)
                    listener?.onData(chunk)
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                val wasClosed = closed
                closed = true
                try {
                    connection.close()
                } catch (_: IOException) {
                }
                if (!wasClosed) listener?.onClosed(failure)
            }
        }
        thread.name = "NshReader"
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    /** 发送原始文本（不自动补换行）。 */
    fun send(text: String) {
        send(text.toByteArray(Charsets.UTF_8))
    }

    fun send(bytes: ByteArray) {
        if (closed) return
        synchronized(writeLock) {
            try {
                connection.write(bytes)
            } catch (error: IOException) {
                // 控制台是可选功能，写失败只影响 console 页
            }
        }
    }

    /** 发送一行命令（自动补 \n）。 */
    fun sendLine(line: String) {
        send(line + "\n")
    }

    /** 取控制台文本快照（已按 UTF-8 解码）。 */
    fun historyText(): String = history.text()

    fun clearHistory() {
        history.clear()
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
        /** 连接控制台 socket；此时 QEMU 的 chardev server 应该已监听。 */
        @Throws(IOException::class)
        fun connect(path: String, timeoutMs: Int = 5000): NshClient {
            val connection = UnixSocketConnection.connect(path, timeoutMs)
            return NshClient(connection)
        }
    }
}
