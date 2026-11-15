package com.zhaoge.miragedroid.net

/**
 * NSH（miwear 的串口 shell）输出处理：把控制台字节流里的命令回显、ANSI 转义和
 * 提示符剥掉，拿到命令输出正文。移植自 Mirage 的 NSHOutput.swift。
 *
 * 固件的提示符形如 `ap> `（<核心名>> + 空格），任何行首的短 "<word>> " 都算提示符。
 */
object NshOutput {
    private val promptRegex = Regex("(?m)^[A-Za-z0-9_.-]{1,16}> ")

    /** 去掉 ANSI 转义序列（CSI）与回车符。 */
    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '\u001B') {
                index++
                if (index < text.length && text[index] == '[') {
                    index++
                    while (index < text.length && text[index] !in '@'..'~') index++
                    if (index < text.length) index++
                }
                continue
            }
            if (c != '\r') out.append(c)
            index++
        }
        return out.toString()
    }

    /** 命令回显之后的部分（找不到回显返回 null）。 */
    fun afterEcho(text: String, command: String): String? {
        val stripped = strip(text)
        val echoAt = stripped.indexOf(command)
        if (echoAt < 0) return null
        val afterCommand = echoAt + command.length
        val newline = stripped.indexOf('\n', afterCommand)
        if (newline < 0) return null
        return stripped.substring(newline + 1)
    }

    fun promptIndex(text: String): Int {
        val match = promptRegex.find(text) ?: return -1
        return match.range.first
    }

    /** 输出里已经出现下一个提示符 → 命令执行完了。 */
    fun isComplete(text: String, command: String): Boolean {
        val rest = afterEcho(text, command) ?: return false
        return promptIndex(rest) >= 0
    }

    /** 取出命令输出正文（回显与提示符之间）。 */
    fun body(text: String, command: String): String {
        val rest = afterEcho(text, command) ?: return strip(text)
        val end = promptIndex(rest)
        val cut = if (end >= 0) rest.substring(0, end) else rest
        return cut.trim('\n')
    }
}

/**
 * 有上限的字节环形缓冲（控制台回滚），新订阅者能拿到历史输出。
 * 超过容量时保留最近 3/4，避免每来一个字节就搬一次内存。
 */
class ByteRing(val capacity: Int) {
    private var data = ByteArray(0)

    val size: Int get() = data.size

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (data.size + bytes.size <= capacity) {
            val merged = ByteArray(data.size + bytes.size)
            System.arraycopy(data, 0, merged, 0, data.size)
            System.arraycopy(bytes, 0, merged, data.size, bytes.size)
            data = merged
            return
        }
        val keep = capacity * 3 / 4
        val source = if (bytes.size >= keep) bytes.copyOfRange(bytes.size - keep, bytes.size) else bytes
        val tail = data.copyOfRange(data.size - minOf(data.size, keep - source.size), data.size)
        val merged = ByteArray(tail.size + source.size)
        System.arraycopy(tail, 0, merged, 0, tail.size)
        System.arraycopy(source, 0, merged, tail.size, source.size)
        data = merged
    }

    fun snapshot(): ByteArray = data.copyOf()

    fun text(): String = String(data, Charsets.UTF_8)

    fun clear() {
        data = ByteArray(0)
    }
}
