package com.zhaoge.miragedroid.net

import java.io.Closeable
import java.io.IOException

/**
 * RFB 3.8 / VNC 客户端：QEMU 的显示输出（-vnc unix:...）。
 *
 * - 握手：版本交换 → SecurityType=None → ClientInit(shared) → ServerInit
 * - 像素格式：32bpp / depth 24 / little-endian / true colour（内存序 B,G,R,X）
 * - 编码：Raw（0）+ DesktopSize（-223），一次只挂一个增量请求
 * - 输入：PointerEvent 注入触摸（坐标是 VNC 面板像素，不是手机屏幕像素）
 *
 * 读循环跑在独立线程；[Listener] 回调也在该线程，界面层自行切主线程。
 */
class VncClient private constructor(private val connection: UnixSocketConnection) : Closeable {
    interface Listener {
        /** 新的一帧（[RfbFrame] 为不可变快照，可在别处保存）。 */
        fun onFrame(frame: RfbFrame)

        fun onResized(width: Int, height: Int)

        fun onClosed(error: Throwable?)
    }

    private val queue = ByteQueue(64 * 1024)
    private val writeLock = Any()
    private val readBuffer = ByteArray(64 * 1024)

    @Volatile
    private var closed = false

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var frameBuffer = FrameBuffer(1, 1)

    @Volatile
    private var lastFrame: RfbFrame? = null

    private var readerThread: Thread? = null
    private var sequence = 0
    private var serverName = ""

    val width: Int get() = frameBuffer.width
    val height: Int get() = frameBuffer.height
    val name: String get() = serverName

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.let { current ->
            lastFrame?.let { current.onFrame(it) }
        }
    }

    /** 最近一帧（可能为 null，表示还没收到完整更新）。 */
    fun latestFrame(): RfbFrame? = lastFrame

    // ------------------------------------------------------------ 握手

    private fun handshake() {
        val version = ByteArray(RfbProtocol.VERSION_LENGTH)
        connection.readFully(version)
        if (!RfbProtocol.isServerVersion(version)) {
            throw IOException("对端不是 VNC/RFB 服务器")
        }
        connection.write(RfbProtocol.buildClientVersion())

        var negotiation: SecurityTypes? = null
        while (negotiation == null) {
            val read = connection.input.read(readBuffer)
            if (read < 0) throw IOException("VNC 连接在安全协商阶段被关闭")
            if (read > 0) queue.append(readBuffer, 0, read)
            negotiation = RfbProtocol.readSecurityTypes(queue)
        }
        if (negotiation.refused) throw IOException("VNC 拒绝连接：${negotiation.reason}")
        if (!negotiation.types.contains(RfbProtocol.SECURITY_NONE)) {
            throw IOException("VNC 需要认证（安全类型 ${negotiation.types}），当前只支持 None")
        }
        connection.write(RfbProtocol.buildSecurityChoice(RfbProtocol.SECURITY_NONE))

        var securityResult: Int? = null
        while (securityResult == null) {
            val read = connection.input.read(readBuffer)
            if (read < 0) throw IOException("VNC 连接在安全握手阶段被关闭")
            if (read > 0) queue.append(readBuffer, 0, read)
            securityResult = RfbProtocol.readSecurityResult(queue)
        }
        if (securityResult != 0) throw IOException("VNC 安全握手失败（结果 $securityResult）")

        connection.write(RfbProtocol.buildClientInit(shared = true))

        var serverInit: ServerInit? = null
        while (serverInit == null) {
            val read = connection.input.read(readBuffer)
            if (read < 0) throw IOException("VNC 连接在 ServerInit 阶段被关闭")
            if (read > 0) queue.append(readBuffer, 0, read)
            serverInit = RfbProtocol.readServerInit(queue)
        }
        serverName = serverInit.name
        frameBuffer = FrameBuffer(serverInit.width, serverInit.height)

        connection.write(RfbProtocol.buildSetPixelFormat(PixelFormat.BGRA32))
        connection.write(
            RfbProtocol.buildSetEncodings(
                intArrayOf(RfbProtocol.ENCODING_RAW, RfbProtocol.ENCODING_DESKTOP_SIZE),
            ),
        )
        connection.write(
            RfbProtocol.buildFramebufferUpdateRequest(
                incremental = false,
                x = 0,
                y = 0,
                width = frameBuffer.width,
                height = frameBuffer.height,
            ),
        )
        queue.clear()
    }

    // ------------------------------------------------------------ 读循环

    private fun startReader() {
        val thread = Thread {
            var failure: Throwable? = null
            try {
                while (!closed) {
                    var progressed = true
                    while (progressed && !closed) progressed = processOneMessage()
                    if (closed) break
                    val read = connection.input.read(readBuffer)
                    if (read < 0) throw IOException("VNC 连接已关闭")
                    if (read > 0) queue.append(readBuffer, 0, read)
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
                listener?.onClosed(if (wasClosed) null else failure)
            }
        }
        thread.name = "VncReader"
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    /** 处理一条完整消息；数据不够返回 false（不消费）。 */
    private fun processOneMessage(): Boolean {
        val type = queue.peek(1)?.get(0)?.toInt()?.and(0xFF) ?: return false
        return when (type) {
            RfbProtocol.MSG_FRAMEBUFFER_UPDATE -> processFramebufferUpdate()
            RfbProtocol.MSG_SET_COLOUR_MAP_ENTRIES -> consumeColourMapEntries()
            RfbProtocol.MSG_BELL -> {
                queue.skip(1)
                true
            }
            RfbProtocol.MSG_SERVER_CUT_TEXT -> consumeServerCutText()
            else -> throw IOException("未知的 VNC 消息类型：$type")
        }
    }

    private fun processFramebufferUpdate(): Boolean {
        val head = queue.peek(4) ?: return false
        val rectCount = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val rects = ArrayList<RfbRect>(rectCount)
        var offset = 4
        for (index in 0 until rectCount) {
            val available = queue.peek(offset + 12) ?: return false
            val rect = RfbRect(
                x = Be.readU16(available, offset),
                y = Be.readU16(available, offset + 2),
                width = Be.readU16(available, offset + 4),
                height = Be.readU16(available, offset + 6),
                encoding = Be.readU32(available, offset + 8).toInt(),
            )
            val dataSize = if (rect.encoding == RfbProtocol.ENCODING_RAW) {
                rect.width * rect.height * 4
            } else {
                0
            }
            rects.add(rect)
            offset += 12 + dataSize
        }
        val payload = queue.peek(offset) ?: return false
        queue.skip(offset)

        var resized = false
        var cursor = 4
        for (rect in rects) {
            cursor += 12
            when (rect.encoding) {
                RfbProtocol.ENCODING_RAW -> {
                    val size = rect.width * rect.height * 4
                    if (size > 0) {
                        frameBuffer.applyRaw(rect, payload.copyOfRange(cursor, cursor + size))
                        cursor += size
                    }
                }
                RfbProtocol.ENCODING_DESKTOP_SIZE -> {
                    if (rect.width > 0 && rect.height > 0) {
                        frameBuffer = FrameBuffer(rect.width, rect.height)
                        resized = true
                        listener?.onResized(rect.width, rect.height)
                    }
                }
                else -> throw IOException("不支持的 VNC 编码：${rect.encoding}")
            }
        }

        sequence++
        val frame = RfbFrame(frameBuffer.width, frameBuffer.height, frameBuffer.pixels.copyOf(), sequence)
        lastFrame = frame
        listener?.onFrame(frame)

        val current = frameBuffer
        connection.write(
            RfbProtocol.buildFramebufferUpdateRequest(
                incremental = !resized,
                x = 0,
                y = 0,
                width = current.width,
                height = current.height,
            ),
        )
        return true
    }

    private fun consumeColourMapEntries(): Boolean {
        val head = queue.peek(6) ?: return false
        val count = Be.readU16(head, 4)
        val total = 6 + count * 6
        if (queue.peek(total) == null) return false
        queue.skip(total)
        return true
    }

    private fun consumeServerCutText(): Boolean {
        val head = queue.peek(8) ?: return false
        val length = Be.readU32(head, 4).toInt()
        if (length < 0) throw IOException("VNC CutText 长度非法")
        val total = 8 + length
        if (queue.peek(total) == null) return false
        queue.skip(total)
        return true
    }

    // ------------------------------------------------------------ 输入

    fun pointer(x: Int, y: Int, pressed: Boolean) {
        if (closed) return
        val width = frameBuffer.width
        val height = frameBuffer.height
        if (width <= 0 || height <= 0) return
        val px = x.coerceIn(0, width - 1)
        val py = y.coerceIn(0, height - 1)
        synchronized(writeLock) {
            connection.write(RfbProtocol.buildPointerEvent(if (pressed) 1 else 0, px, py))
        }
    }

    /** 按 0..1 的比例注入触摸（界面把手机坐标换算成面板比例）。 */
    fun pointerRatio(xRatio: Float, yRatio: Float, pressed: Boolean) {
        val width = frameBuffer.width
        val height = frameBuffer.height
        if (width <= 0 || height <= 0) return
        val x = (xRatio.coerceIn(0f, 1f) * (width - 1)).toInt()
        val y = (yRatio.coerceIn(0f, 1f) * (height - 1)).toInt()
        pointer(x, y, pressed)
    }

    fun key(keysym: Long, down: Boolean) {
        if (closed) return
        synchronized(writeLock) {
            connection.write(RfbProtocol.buildKeyEvent(keysym, down))
        }
    }

    fun keysymForQcode(qcode: String): Long? = when (qcode) {
        "home" -> 0xFF50L
        "pgup" -> 0xFF55L
        "pgdn", "power" -> 0xFF56L
        "esc" -> 0xFF1BL
        "ret", "enter" -> 0xFF0DL
        else -> null
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
        /** 连接并完成 RFB 握手；超时/协议错误抛 IOException。 */
        @Throws(IOException::class)
        fun connect(path: String, timeoutMs: Int = 8000): VncClient {
            val connection = UnixSocketConnection.connect(path, timeoutMs)
            val client = VncClient(connection)
            try {
                client.handshake()
            } catch (error: Throwable) {
                client.close()
                if (error is IOException) throw error
                throw IOException("VNC 握手失败：${error.message}", error)
            }
            client.startReader()
            return client
        }
    }
}
