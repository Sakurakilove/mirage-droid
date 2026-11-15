package com.zhaoge.miragedroid.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbCodecTest {
    @Test
    fun serverVersionDetection() {
        assertTrue(RfbProtocol.isServerVersion("RFB 003.008\n".toByteArray(Charsets.US_ASCII)))
        assertTrue(RfbProtocol.isServerVersion("RFB 003.889\n".toByteArray(Charsets.US_ASCII)))
        assertTrue(!RfbProtocol.isServerVersion("HTTP/1.1 200".toByteArray(Charsets.US_ASCII)))
        assertTrue(!RfbProtocol.isServerVersion("RFB 003.008".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun clientVersionAndSecurityChoice() {
        assertArrayEquals("RFB 003.008\n".toByteArray(Charsets.US_ASCII), RfbProtocol.buildClientVersion())
        assertArrayEquals(byteArrayOf(1), RfbProtocol.buildSecurityChoice(RfbProtocol.SECURITY_NONE))
        assertArrayEquals(byteArrayOf(1), RfbProtocol.buildClientInit(true))
        assertArrayEquals(byteArrayOf(0), RfbProtocol.buildClientInit(false))
    }

    @Test
    fun readsSecurityTypesWithFragments() {
        val queue = ByteQueue()
        queue.append(byteArrayOf(2, 1))
        assertNull("数据不够时应返回 null", RfbProtocol.readSecurityTypes(queue))
        queue.append(byteArrayOf(2))
        val types = RfbProtocol.readSecurityTypes(queue)
        assertNotNull(types)
        assertEquals(listOf(1, 2), types!!.types)
        assertNull(types.reason)
        assertTrue(!types.refused)
        assertEquals(0, queue.size)
    }

    @Test
    fun readsRefusalReason() {
        val reason = "no security".toByteArray(Charsets.UTF_8)
        val payload = Be.concat(byteArrayOf(0), Be.u32(reason.size.toLong()), reason)
        val queue = ByteQueue()
        queue.append(payload)
        val types = RfbProtocol.readSecurityTypes(queue)
        assertNotNull(types)
        assertTrue(types!!.refused)
        assertEquals("no security", types.reason)
    }

    @Test
    fun readsSecurityResult() {
        val queue = ByteQueue()
        queue.append(byteArrayOf(0, 0, 0, 0))
        assertEquals(0, RfbProtocol.readSecurityResult(queue))
        val failed = ByteQueue()
        failed.append(byteArrayOf(0, 0, 0, 1))
        assertEquals(1, RfbProtocol.readSecurityResult(failed))
        assertNull(RfbProtocol.readSecurityResult(ByteQueue()))
    }

    @Test
    fun readsServerInitWhenComplete() {
        val name = "QEMU".toByteArray(Charsets.UTF_8)
        val payload = Be.concat(
            Be.u16(432), Be.u16(514),
            PixelFormat.BGRA32.toBytes(),
            Be.u32(name.size.toLong()), name,
        )
        val queue = ByteQueue()
        queue.append(payload.copyOfRange(0, 20))
        assertNull("ServerInit 未收全时返回 null", RfbProtocol.readServerInit(queue))
        assertTrue("未收全时不能消费数据", queue.size > 0)
        queue.append(payload.copyOfRange(20, payload.size))
        val init = RfbProtocol.readServerInit(queue)
        assertNotNull(init)
        assertEquals(432, init!!.width)
        assertEquals(514, init.height)
        assertEquals("QEMU", init.name)
        assertEquals(32, init.pixelFormat.bitsPerPixel)
        assertEquals(16, init.pixelFormat.redShift)
        assertEquals(0, queue.size)
    }

    @Test
    fun pixelFormatBytes() {
        val bytes = PixelFormat.BGRA32.toBytes()
        assertEquals(16, bytes.size)
        assertEquals(32, bytes[0].toInt() and 0xFF)
        assertEquals(24, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt() and 0xFF)
        assertEquals(1, bytes[3].toInt() and 0xFF)
        assertEquals(255, Be.readU16(bytes, 4))
        assertEquals(255, Be.readU16(bytes, 6))
        assertEquals(255, Be.readU16(bytes, 8))
        assertEquals(16, bytes[10].toInt() and 0xFF)
        assertEquals(8, bytes[11].toInt() and 0xFF)
        assertEquals(0, bytes[12].toInt() and 0xFF)
        assertEquals(PixelFormat.BGRA32, PixelFormat.parse(bytes, 0))
    }

    @Test
    fun setPixelFormatMessage() {
        val bytes = RfbProtocol.buildSetPixelFormat()
        assertEquals(20, bytes.size)
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertEquals(32, bytes[4].toInt())
        assertEquals(24, bytes[5].toInt())
        assertEquals(1, bytes[7].toInt())
        assertEquals(16, bytes[14].toInt())
        assertEquals(8, bytes[15].toInt())
        assertEquals(0, bytes[16].toInt())
    }

    @Test
    fun setEncodingsMessage() {
        val bytes = RfbProtocol.buildSetEncodings(
            intArrayOf(RfbProtocol.ENCODING_RAW, RfbProtocol.ENCODING_DESKTOP_SIZE),
        )
        assertEquals(12, bytes.size)
        assertEquals(2, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(2, Be.readU16(bytes, 2))
        assertEquals(0L, Be.readU32(bytes, 4))
        assertEquals(0xFFFFFF21L, Be.readU32(bytes, 8))
    }

    @Test
    fun framebufferUpdateRequestMessage() {
        val bytes = RfbProtocol.buildFramebufferUpdateRequest(
            incremental = false, x = 0, y = 0, width = 432, height = 514,
        )
        assertEquals(10, bytes.size)
        assertEquals(3, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, Be.readU16(bytes, 2))
        assertEquals(0, Be.readU16(bytes, 4))
        assertEquals(432, Be.readU16(bytes, 6))
        assertEquals(514, Be.readU16(bytes, 8))
        assertEquals(1, RfbProtocol.buildFramebufferUpdateRequest(true, 0, 0, 1, 1)[1].toInt())
    }

    @Test
    fun pointerAndKeyEvents() {
        val pointer = RfbProtocol.buildPointerEvent(buttons = 1, x = 10, y = 20)
        assertArrayEquals(byteArrayOf(5, 1, 0, 10, 0, 20), pointer)
        assertEquals(0, RfbProtocol.buildPointerEvent(0, 10, 20)[1].toInt())

        val key = RfbProtocol.buildKeyEvent(0x0000_FF1BL, down = true)
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xFF.toByte(), 0x1B), key)
        assertArrayEquals(byteArrayOf(4, 0, 0, 0, 0, 0, 0xFF.toByte(), 0x1B), RfbProtocol.buildKeyEvent(0xFF1B, false))
    }

    @Test
    fun readsFramebufferUpdateHeaderAndRects() {
        val queue = ByteQueue()
        queue.append(byteArrayOf(0, 0, 2))
        assertEquals(2, RfbProtocol.readUpdateHeader(queue))
        assertEquals(-1, RfbProtocol.readUpdateHeader(ByteQueue()))

        queue.append(Be.concat(Be.u16(0), Be.u16(0), Be.u16(432), Be.u16(20), Be.i32(-223)))
        val rect = RfbProtocol.readRectHeader(queue)
        assertNotNull(rect)
        assertEquals(432, rect!!.width)
        assertEquals(20, rect.height)
        assertEquals(-223, rect.encoding)
        assertNull(RfbProtocol.readRectHeader(queue))
    }

    @Test
    fun readsCutText() {
        val text = "hello".toByteArray(Charsets.UTF_8)
        val payload = Be.concat(byteArrayOf(0, 0, 0), Be.u32(text.size.toLong()), text)
        val queue = ByteQueue()
        queue.append(payload)
        assertEquals("hello", RfbProtocol.readServerCutText(queue))
        assertNull(RfbProtocol.readServerCutText(ByteQueue()))
    }

    @Test
    fun frameBufferConvertsBgraToArgb() {
        val frame = FrameBuffer(4, 3)
        // 覆盖左上角 2x2：红色
        val red = byteArrayOf(0, 0, 255.toByte(), 0)
        val redBlock = ByteArray(16)
        for (index in 0 until 4) System.arraycopy(red, 0, redBlock, index * 4, 4)
        frame.applyRaw(RfbRect(0, 0, 2, 2, RfbProtocol.ENCODING_RAW), redBlock)
        assertEquals(0xFFFF0000.toInt(), frame.pixels[0])
        assertEquals(0xFFFF0000.toInt(), frame.pixels[1])
        assertEquals(0xFFFF0000.toInt(), frame.pixels[4])
        assertEquals(0xFF000000.toInt(), frame.pixels[2])

        // 越界矩形不能抛异常，也不能写坏别的像素
        frame.applyRaw(RfbRect(3, 2, 4, 4, RfbProtocol.ENCODING_RAW), ByteArray(64))
        assertEquals("越界外的旧像素不能被改动", 0xFFFF0000.toInt(), frame.pixels[5])
        assertEquals("落在面板内的部分要正常写入", 0xFF000000.toInt(), frame.pixels[11])
    }

    @Test
    fun frameBufferReadsRgbComponents() {
        val frame = FrameBuffer(1, 1)
        frame.applyRaw(
            RfbRect(0, 0, 1, 1, RfbProtocol.ENCODING_RAW),
            byteArrayOf(0x11, 0x22, 0x33, 0),
        )
        assertEquals(0xFF332211.toInt(), frame.pixels[0])
    }
}
