package com.zhaoge.miragedroid.net

import com.zhaoge.miragedroid.data.Json
import com.zhaoge.miragedroid.data.JsonValue
import com.zhaoge.miragedroid.data.field
import com.zhaoge.miragedroid.data.jb
import com.zhaoge.miragedroid.data.jn
import com.zhaoge.miragedroid.data.js
import com.zhaoge.miragedroid.data.jsonArray
import com.zhaoge.miragedroid.data.jsonObject
import com.zhaoge.miragedroid.data.str

/**
 * QEMU Machine Protocol（QMP）编解码：JSON 行协议。
 *
 * 纯逻辑（不碰 socket），VncClient 同款做法，便于单测。消息形态：
 * ```
 * 服务器 → {"QMP": {...}}                      问候
 * 客户端 → {"execute":"qmp_capabilities","id":1}
 * 服务器 → {"return":{},"id":1}                成功
 * 服务器 → {"error":{"class":"...","desc":"..."},"id":1}
 * 服务器 → {"event":"SHUTDOWN","data":{...}}   事件
 * ```
 */
sealed class QmpMessage {
    data class Greeting(val payload: JsonValue?) : QmpMessage()

    data class Response(
        val id: JsonValue?,
        val result: JsonValue?,
        val error: QmpError?,
    ) : QmpMessage()

    data class Event(val name: String, val data: JsonValue?) : QmpMessage()

    data class Malformed(val line: String) : QmpMessage()
}

data class QmpError(val klass: String, val desc: String) {
    override fun toString(): String = if (klass.isEmpty()) desc else "$klass: $desc"
}

data class QmpCommand(val name: String, val arguments: JsonValue.Obj? = null)

object QmpProtocol {
    /** 把一行 JSON 文本解析成消息；无法识别返回 Malformed。 */
    fun parseLine(line: String): QmpMessage {
        if (line.isBlank()) return QmpMessage.Malformed(line)
        val root = Json.parseOrNull(line) ?: return QmpMessage.Malformed(line)
        if (root is JsonValue.Obj) {
            if (root.fields.containsKey("QMP")) return QmpMessage.Greeting(root.field("QMP"))
            val event = root.field("event")
            if (event is JsonValue.Str) return QmpMessage.Event(event.value, root.field("data"))
            if (root.fields.containsKey("return") || root.fields.containsKey("error")) {
                val errorValue = root.field("error")
                val error = (errorValue as? JsonValue.Obj)?.let {
                    QmpError(it.str("class"), it.str("desc", errorValue.encode()))
                }
                return QmpMessage.Response(root.field("id"), root.field("return"), error)
            }
        }
        return QmpMessage.Malformed(line)
    }

    /** 编码一条命令（带自增 id），返回单行 JSON（不含换行）。 */
    fun encode(command: QmpCommand, id: Long): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["execute"] = js(command.name)
        if (command.arguments != null) fields["arguments"] = command.arguments
        fields["id"] = jn(id)
        return jsonObject(fields).encode()
    }

    fun encodeLine(command: QmpCommand, id: Long): ByteArray =
        (encode(command, id) + "\n").toByteArray(Charsets.UTF_8)

    /** 从 response 里取整数（qom-get 的电池电量等）。 */
    fun responseInt(message: QmpMessage): Int? {
        val response = message as? QmpMessage.Response ?: return null
        val result = response.result ?: return null
        return when (result) {
            is JsonValue.Num -> result.value.toInt()
            is JsonValue.Bool -> if (result.value) 1 else 0
            else -> null
        }
    }

    fun responseBool(message: QmpMessage): Boolean? {
        val response = message as? QmpMessage.Response ?: return null
        return (response.result as? JsonValue.Bool)?.value
    }

    fun responseString(message: QmpMessage): String? {
        val response = message as? QmpMessage.Response ?: return null
        return (response.result as? JsonValue.Str)?.value
    }
}

/** 把 socket 碎片切成完整 JSON 行。 */
class QmpLineReader {
    private val queue = ByteQueue(8192)

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<String> {
        queue.append(bytes, offset, length)
        val lines = ArrayList<String>()
        while (true) {
            val all = queue.peek(queue.size) ?: break
            var newline = -1
            for (index in all.indices) {
                if (all[index] == '\n'.code.toByte()) {
                    newline = index
                    break
                }
            }
            if (newline < 0) break
            val lineBytes = all.copyOfRange(0, newline)
            queue.skip(newline + 1)
            lines.add(String(lineBytes, Charsets.UTF_8).trimEnd('\r'))
        }
        queue.compact()
        return lines
    }
}

/** 常用 QMP 命令构造。 */
object QmpCommands {
    fun capabilities(): QmpCommand = QmpCommand("qmp_capabilities")

    fun queryStatus(): QmpCommand = QmpCommand("query-status")

    fun quit(): QmpCommand = QmpCommand("quit")

    /** 暂停/继续虚拟机（gdb 调试或 shell 模式用）。 */
    fun stopMachine(): QmpCommand = QmpCommand("stop")

    fun contMachine(): QmpCommand = QmpCommand("cont")

    /** 一次 key 事件（qcode 如 "home" / "pgdn"）。 */
    fun inputKey(qcode: String, down: Boolean): QmpCommand = QmpCommand(
        "input-send-event",
        jsonObject(
            "events" to jsonArray(
                jsonObject(
                    "type" to js("key"),
                    "data" to jsonObject(
                        "down" to jb(down),
                        "key" to jsonObject(
                            "type" to js("qcode"),
                            "data" to js(qcode),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * 绝对坐标触摸/鼠标事件，x、y 为 0..1 的面板比例。
     * 触摸屏走 abs 轴，[down] 为 null 时只移动不按键。
     */
    fun inputTouch(x: Double, y: Double, down: Boolean?): QmpCommand {
        val events = ArrayList<JsonValue>()
        events.add(absAxis("x", x))
        events.add(absAxis("y", y))
        if (down != null) {
            events.add(
                jsonObject(
                    "type" to js("btn"),
                    "data" to jsonObject("down" to jb(down), "button" to js("left")),
                ),
            )
        }
        return QmpCommand("input-send-event", jsonObject("events" to jsonArray(events)))
    }

    private fun absAxis(axis: String, value: Double): JsonValue {
        val scaled = (value.coerceIn(0.0, 1.0) * 32767.0).toInt()
        return jsonObject(
            "type" to js("abs"),
            "data" to jsonObject("axis" to js(axis), "value" to jn(scaled)),
        )
    }

    fun qomGet(path: String, property: String): QmpCommand =
        QmpCommand("qom-get", jsonObject("path" to js(path), "property" to js(property)))

    fun qomSet(path: String, property: String, value: JsonValue): QmpCommand =
        QmpCommand("qom-set", jsonObject("path" to js(path), "property" to js(property), "value" to value))

    fun screendump(filename: String): QmpCommand =
        QmpCommand("screendump", jsonObject("filename" to js(filename), "format" to js("png")))

    /** 人机监视器（HMP）命令，用于兼容一些只有 HMP 提供的查询。 */
    fun humanMonitor(commandLine: String): QmpCommand =
        QmpCommand("human-monitor-command", jsonObject("command-line" to js(commandLine)))
}
