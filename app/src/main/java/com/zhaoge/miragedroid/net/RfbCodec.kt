package com.zhaoge.miragedroid.net

/**
 * RFB 3.8 协议编解码（QEMU 的 VNC 服务器，SecurityType=None，Raw 编码）。
 *
 * 这里只做纯字节处理，不碰 socket，因此可以在 JVM 单测里逐字段断言；
 * 连接与读循环在 VncClient。
 */
object RfbProtocol {
    const val CLIENT_VERSION = "RFB 003.008\n"
    const val SERVER_VERSION_PREFIX = "RFB 003."
    const val VERSION_LENGTH = 12

    const val SECURITY_NONE = 1
    const val SECURITY_VNC_AUTH = 2

    const val ENCODING_RAW = 0
    const val ENCODING_COPY_RECT = 1
    const val ENCODING_DESKTOP_SIZE = -223

    const val MSG_FRAMEBUFFER_UPDATE = 0
    const val MSG_SET_COLOUR_MAP_ENTRIES = 1
    const val MSG_BELL = 2
    const val MSG_SERVER_CUT_TEXT = 3

    // ---------------------------------------------------------- 握手输入

    fun isServerVersion(bytes: ByteArray): Boolean {
        if (bytes.size < VERSION_LENGTH) return false
        val text = String(bytes, 0, VERSION_LENGTH, Charsets.US_ASCII)
        return text.startsWith(SERVER_VERSION_PREFIX) && text.endsWith("\n")
    }

    fun buildClientVersion(): ByteArray = CLIENT_VERSION.toByteArray(Charsets.US_ASCII)

    fun buildSecurityChoice(securityType: Int): ByteArray = Be.u8(securityType)

    fun buildClientInit(shared: Boolean): ByteArray = Be.u8(if (shared) 1 else 0)

    /** 读服务器发来的安全类型列表；数据不够时返回 null（不消费）。 */
    fun readSecurityTypes(queue: ByteQueue): SecurityTypes? {
        val head = queue.peek(1) ?: return null
        val count = head[0].toInt() and 0xFF
        if (count == 0) {
            // 拒绝：1 字节 0 + 4 字节 reason 长度 + reason
            val fixed = queue.peek(5) ?: return null
            val reasonLength = Be.readU32(fixed, 1).toInt()
            if (reasonLength < 0) return null
            val total = 5 + reasonLength
            val all = queue.peek(total) ?: return null
            queue.skip(total)
            val reason = String(all, 5, reasonLength, Charsets.UTF_8)
            return SecurityTypes(types = emptyList(), reason = reason)
        }
        val all = queue.peek(1 + count) ?: return null
        queue.skip(1 + count)
        val types = ArrayList<Int>(count)
        for (index in 0 until count) types.add(all[1 + index].toInt() and 0xFF)
        return SecurityTypes(types = types, reason = null)
    }

    /** 安全握手结果：0 = 成功；数据不够返回 null。 */
    fun readSecurityResult(queue: ByteQueue): Int? {
        val bytes = queue.peek(4) ?: return null
        queue.skip(4)
        return Be.readU32(bytes, 0).toInt()
    }

    fun readServerInit(queue: ByteQueue): ServerInit? {
        val head = queue.peek(24) ?: return null
        val width = Be.readU16(head, 0)
        val height = Be.readU16(head, 2)
        val pixelFormat = PixelFormat.parse(head, 4)
        val nameLength = Be.readU32(head, 20).toInt()
        if (nameLength < 0) return null
        val total = 24 + nameLength
        val all = queue.peek(total) ?: return null
        queue.skip(total)
        val name = if (nameLength > 0) {
            String(all, 24, nameLength, Charsets.UTF_8)
        } else {
            ""
        }
        return ServerInit(width, height, pixelFormat, name)
    }

    // ---------------------------------------------------------- 客户端输出

    /**
     * SetPixelFormat：32bpp / depth 24 / little-endian / true colour，
     * 内存序 B,G,R,X → 解析成 0xAARRGGBB 时 R 在 bit16。
     */
    fun buildSetPixelFormat(pixelFormat: PixelFormat = PixelFormat.BGRA32): ByteArray =
        Be.concat(Be.u8(0), Be.u8(0), Be.u8(0), Be.u8(0), pixelFormat.toBytes())

    fun buildSetEncodings(encodings: IntArray): ByteArray {
        val length = 4 + encodings.size * 4
        val out = ByteArray(length)
        out[0] = 2 // SetEncodings
        out[1] = 0
        val count = encodings.size
        out[2] = ((count ushr 8) and 0xFF).toByte()
        out[3] = (count and 0xFF).toByte()
        var offset = 4
        for (encoding in encodings) {
            val bytes = Be.i32(encoding)
            System.arraycopy(bytes, 0, out, offset, 4)
            offset += 4
        }
        return out
    }

    fun buildFramebufferUpdateRequest(
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ByteArray = Be.concat(
        Be.u8(3),
        Be.u8(if (incremental) 1 else 0),
        Be.u16(x), Be.u16(y), Be.u16(width), Be.u16(height),
    )

    /** PointerEvent：buttons 位 0 = 左键按下（触摸）。 */
    fun buildPointerEvent(buttons: Int, x: Int, y: Int): ByteArray =
        Be.concat(Be.u8(5), Be.u8(buttons and 0xFF), Be.u16(x), Be.u16(y))

    fun buildKeyEvent(keysym: Long, down: Boolean): ByteArray =
        Be.concat(Be.u8(4), Be.u8(if (down) 1 else 0), Be.u8(0), Be.u8(0), Be.u32(keysym and 0xFFFFFFFFL))

    // ---------------------------------------------------------- 服务器消息

    /**
     * 读取 FramebufferUpdate 的消息头（1 字节 padding + 2 字节矩形数）。
     * 调用方已经读走了消息类型字节；数据不够返回 -1（不消费）。
     */
    fun readUpdateHeader(queue: ByteQueue): Int {
        val head = queue.peek(3) ?: return -1
        queue.skip(3)
        return ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
    }

    /** 读取一个矩形头（12 字节）；不够返回 null。 */
    fun readRectHeader(queue: ByteQueue): RfbRect? {
        val bytes = queue.peek(12) ?: return null
        queue.skip(12)
        return RfbRect(
            x = Be.readU16(bytes, 0),
            y = Be.readU16(bytes, 2),
            width = Be.readU16(bytes, 4),
            height = Be.readU16(bytes, 6),
            encoding = Be.readU32(bytes, 8).toInt(),
        )
    }

    /** ServerCutText：3 字节 padding + 4 字节长度 + 文本。 */
    fun readServerCutText(queue: ByteQueue): String? {
        val head = queue.peek(7) ?: return null
        val length = Be.readU32(head, 3).toInt()
        if (length < 0) return null
        val all = queue.peek(7 + length) ?: return null
        queue.skip(7 + length)
        return String(all, 7, length, Charsets.UTF_8)
    }
}

/** 服务器给出的安全类型；[reason] 非空表示服务器拒绝连接。 */
data class SecurityTypes(val types: List<Int>, val reason: String?) {
    val refused: Boolean get() = reason != null
}

data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    fun toBytes(): ByteArray = Be.concat(
        Be.u8(bitsPerPixel),
        Be.u8(depth),
        Be.u8(if (bigEndian) 1 else 0),
        Be.u8(if (trueColour) 1 else 0),
        Be.u16(redMax), Be.u16(greenMax), Be.u16(blueMax),
        Be.u8(redShift), Be.u8(greenShift), Be.u8(blueShift),
        Be.u8(0), Be.u8(0), Be.u8(0),
    )

    companion object {
        /** 内存里是小端 32 位：B,G,R,X → int 值 0x00RRGGBB。 */
        val BGRA32 = PixelFormat(
            bitsPerPixel = 32,
            depth = 24,
            bigEndian = false,
            trueColour = true,
            redMax = 255,
            greenMax = 255,
            blueMax = 255,
            redShift = 16,
            greenShift = 8,
            blueShift = 0,
        )

        fun parse(bytes: ByteArray, offset: Int): PixelFormat = PixelFormat(
            bitsPerPixel = bytes[offset].toInt() and 0xFF,
            depth = bytes[offset + 1].toInt() and 0xFF,
            bigEndian = (bytes[offset + 2].toInt() and 0xFF) != 0,
            trueColour = (bytes[offset + 3].toInt() and 0xFF) != 0,
            redMax = Be.readU16(bytes, offset + 4),
            greenMax = Be.readU16(bytes, offset + 6),
            blueMax = Be.readU16(bytes, offset + 8),
            redShift = bytes[offset + 10].toInt() and 0xFF,
            greenShift = bytes[offset + 11].toInt() and 0xFF,
            blueShift = bytes[offset + 12].toInt() and 0xFF,
        )
    }
}

data class ServerInit(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val name: String,
)

data class RfbRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val encoding: Int,
)

/**
 * 面板像素缓冲：内部是 0xAARRGGBB 的 IntArray，方便直接喂给 Android Bitmap。
 * 把 VNC 的 BGRA 32bpp 原始数据搬进来。
 */
class FrameBuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height) { 0xFF000000.toInt() }

    fun applyRaw(rect: RfbRect, bgra: ByteArray) {
        val rectWidth = rect.width
        val rectHeight = rect.height
        var source = 0
        for (row in 0 until rectHeight) {
            val y = rect.y + row
            if (y < 0 || y >= height) {
                source += rectWidth * 4
                continue
            }
            var destination = y * width + rect.x
            for (column in 0 until rectWidth) {
                val x = rect.x + column
                if (x in 0 until width) {
                    val b = bgra[source].toInt() and 0xFF
                    val g = bgra[source + 1].toInt() and 0xFF
                    val r = bgra[source + 2].toInt() and 0xFF
                    pixels[destination] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
                source += 4
                destination++
            }
        }
    }
}

/** 一帧已完成的画面（不可变快照，UI 线程可直接使用）。 */
class RfbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val sequence: Int,
) {
    fun isEmpty(): Boolean = width <= 0 || height <= 0
}
