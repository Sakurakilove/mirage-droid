package com.zhaoge.miragedroid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NshCodecTest {
    @Test
    fun stripsAnsiAndCarriageReturns() {
        assertEquals("hello\n", NshOutput.strip("\u001B[0mhello\r\n"))
        assertEquals("abc", NshOutput.strip("\u001B[2Kabc"))
        assertEquals("plain", NshOutput.strip("plain"))
        assertEquals("中文输出", NshOutput.strip("\u001B[32m中文输出\u001B[0m"))
    }

    @Test
    fun findsEchoAndBody() {
        val text = "ap> ls\r\nfile1\r\nfile2\r\nap> "
        val rest = NshOutput.afterEcho(text, "ls")
        assertEquals("file1\nfile2\nap> ", rest)
        assertTrue(NshOutput.isComplete(text, "ls"))
        assertEquals("file1\nfile2", NshOutput.body(text, "ls"))
    }

    @Test
    fun incompleteCommandHasNoPrompt() {
        val text = "ap> ls\r\nfile1\r\n"
        assertFalse(NshOutput.isComplete(text, "ls"))
        assertEquals("file1", NshOutput.body(text, "ls"))
    }

    @Test
    fun missingEchoReturnsRawText() {
        val text = "some boot log\nap> "
        assertNull(NshOutput.afterEcho(text, "ls"))
        assertFalse(NshOutput.isComplete(text, "ls"))
        assertEquals(text, NshOutput.body(text, "ls"))
    }

    @Test
    fun promptPatternAcceptsShortWordsOnly() {
        assertTrue(NshOutput.promptIndex("ap> ") >= 0)
        assertTrue(NshOutput.promptIndex("root> ") >= 0)
        assertTrue(NshOutput.promptIndex("a_b-1.c> ") >= 0)
        assertEquals("行首才认提示符", -1, NshOutput.promptIndex("log line ap> "))
        assertEquals("没有空格不算提示符", -1, NshOutput.promptIndex("ap>"))
        assertEquals("超过 16 字符不算提示符", -1, NshOutput.promptIndex("abcdefghijklmnopq> "))
    }

    @Test
    fun bodyHandlesMultilineEcho() {
        val text = "ap> help\r\ncommands:\r\n  ls\r\n  ps\r\nap> "
        assertEquals("commands:\n  ls\n  ps", NshOutput.body(text, "help"))
    }

    @Test
    fun byteRingKeepsTail() {
        val ring = ByteRing(10)
        ring.append("0123456789".toByteArray(Charsets.UTF_8))
        assertEquals("0123456789", ring.text())
        ring.append("abc".toByteArray(Charsets.UTF_8))
        assertTrue(ring.size <= 10)
        assertTrue("必须保留最新数据: ${ring.text()}", ring.text().endsWith("abc"))
        assertTrue("也必须保留一部分历史: ${ring.text()}", ring.text().contains("6789"))
    }

    @Test
    fun byteRingHandlesOversizedAppend() {
        val ring = ByteRing(10)
        ring.append("0123456789".toByteArray(Charsets.UTF_8))
        ring.append("ABCDEFGHIJKLMNOPQRST".toByteArray(Charsets.UTF_8))
        assertTrue(ring.size <= 10)
        // 新数据比容量还大时，只保留它末尾的 3/4 容量
        assertEquals("NOPQRST", ring.text())
    }

    @Test
    fun byteRingSnapshotsAndClears() {
        val ring = ByteRing(64)
        ring.append("boot ".toByteArray(Charsets.UTF_8))
        ring.append("done".toByteArray(Charsets.UTF_8))
        val snapshot = ring.snapshot()
        assertEquals("boot done", String(snapshot, Charsets.UTF_8))
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals("", ring.text())
    }

    @Test
    fun byteRingKeepsUtf8IntactAcrossAppends() {
        val ring = ByteRing(64)
        val text = "串口输出正常"
        val bytes = text.toByteArray(Charsets.UTF_8)
        ring.append(bytes.copyOfRange(0, 2))
        ring.append(bytes.copyOfRange(2, bytes.size))
        assertEquals(text, ring.text())
    }
}
