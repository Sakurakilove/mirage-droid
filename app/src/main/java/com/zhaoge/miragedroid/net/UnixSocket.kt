package com.zhaoge.miragedroid.net

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android 上的 unix domain socket 连接（QEMU 的 -vnc/-qmp/chardev 都用它）。
 *
 * Android 没有 java.net 的 unix socket 支持，只能走 [LocalSocket] 的文件系统
 * 命名空间；路径是 app cacheDir 下的短路径（unix socket 有 104 字节上限）。
 */
class UnixSocketConnection private constructor(private val socket: LocalSocket) : Closeable {
    val input: InputStream = socket.inputStream
    val output: OutputStream = socket.outputStream

    @Volatile
    private var closed = false

    fun connectTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    /** 阻塞读满 [length] 字节；对端关闭时抛 IOException。 */
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var read = 0
        while (read < length) {
            val count = input.read(buffer, offset + read, length - read)
            if (count < 0) throw IOException("连接已关闭")
            read += count
        }
    }

    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    val isClosed: Boolean get() = closed

    override fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
            // 关闭失败无所谓：进程退出/对端断开时本来就会报错
        }
    }

    companion object {
        /**
         * 连接一个 unix socket 文件；QEMU 刚启动时监听可能晚几百毫秒，
         * 所以在 [timeoutMs] 内按 150ms 间隔重试。
         */
        fun connect(path: String, timeoutMs: Int = 5000): UnixSocketConnection {
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastError: IOException? = null
            while (true) {
                val socket = LocalSocket()
                try {
                    socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                    socket.soTimeout = 0
                    return UnixSocketConnection(socket)
                } catch (error: IOException) {
                    lastError = error
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    if (System.currentTimeMillis() >= deadline) break
                    try {
                        Thread.sleep(150)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("连接 $path 被中断", interrupted)
                    }
                }
            }
            throw IOException("连接 $path 超时：${lastError?.message}", lastError)
        }
    }
}
