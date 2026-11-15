package com.zhaoge.miragedroid.net

/**
 * 一个只进不出的字节队列：socket 读到的碎片先攒在这里，协议层按需取整包。
 * 纯 Kotlin/Java 实现，可在 JVM 单测里直接喂字节验证。
 */
class ByteQueue(initialCapacity: Int = 4096) {
    private var data = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        ensure(end + length)
        System.arraycopy(bytes, offset, data, end, length)
        end += length
    }

    fun append(bytes: ByteArray) = append(bytes, 0, bytes.size)

    fun clear() {
        start = 0
        end = 0
    }

    /** 取出一个字节；不够时返回 -1。 */
    fun readU8(): Int {
        if (size < 1) return -1
        val value = data[start].toInt() and 0xFF
        start++
        return value
    }

    fun peek(range: Int): ByteArray? {
        if (size < range) return null
        return data.copyOfRange(start, start + range)
    }

    fun readU16(): Int {
        if (size < 2) return -1
        val value = ((data[start].toInt() and 0xFF) shl 8) or (data[start + 1].toInt() and 0xFF)
        start += 2
        return value
    }

    fun readU32(): Int {
        if (size < 4) return -1
        val value = ((data[start].toInt() and 0xFF) shl 24) or
            ((data[start + 1].toInt() and 0xFF) shl 16) or
            ((data[start + 2].toInt() and 0xFF) shl 8) or
            (data[start + 3].toInt() and 0xFF)
        start += 4
        return value
    }

    fun readU32AsLong(): Long = readU32().toLong() and 0xFFFFFFFFL

    /** 取出 [count] 字节；不够时返回 null 且不消费。 */
    fun readBytes(count: Int): ByteArray? {
        if (count < 0 || size < count) return null
        val out = data.copyOfRange(start, start + count)
        start += count
        return out
    }

    fun skip(count: Int): Boolean {
        if (size < count) return false
        start += count
        return true
    }

    /** 丢弃已消费的前缀，防止队列无限增长。 */
    fun compact() {
        if (start == 0) return
        if (start == end) {
            start = 0
            end = 0
            return
        }
        val remaining = end - start
        if (start > 2048 || start > data.size / 2) {
            System.arraycopy(data, start, data, 0, remaining)
            start = 0
            end = remaining
        }
    }

    private fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var newSize = data.size
        while (newSize < capacity) newSize *= 2
        val grown = ByteArray(newSize)
        System.arraycopy(data, start, grown, 0, end - start)
        data = grown
        end -= start
        start = 0
    }
}

/** RFB 风格的 big-endian 写入辅助。 */
object Be {
    fun u8(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte())

    fun u16(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun u32(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun i32(value: Int): ByteArray = u32(value.toLong() and 0xFFFFFFFFL)

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (part in parts) total += part.size
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
