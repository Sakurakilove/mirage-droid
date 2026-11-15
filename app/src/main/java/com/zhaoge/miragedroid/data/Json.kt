package com.zhaoge.miragedroid.data

/**
 * 极简 JSON 解析 / 序列化实现。
 *
 * 为什么不用 org.json：JVM 单测里 android.jar 的 org.json 只是空壳（调用会抛
 * "not mocked"），而数据层要能在 app/src/test 下完整验证；kotlinx.serialization
 * 又要引入编译器插件依赖。本实现只覆盖 App 需要的子集（对象/数组/字符串/
 * 数字/布尔/null），零外部依赖，Android 与 JVM 行为完全一致。
 */
sealed class JsonValue {
    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    /** 序列化为紧凑 JSON 文本。 */
    fun encode(): String {
        val sb = StringBuilder()
        writeTo(sb)
        return sb.toString()
    }

    fun writeTo(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> writeNumber(sb, value)
            is Str -> writeString(sb, value)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    item.writeTo(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((key, item) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key)
                    sb.append(':')
                    item.writeTo(sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeNumber(sb: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            sb.append("null")
            return
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            sb.append(value.toLong().toString())
        } else {
            sb.append(value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    override fun toString(): String = encode()
}

// ---------------------------------------------------------------- 访问器

fun JsonValue.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

fun JsonValue.objOrEmpty(name: String): Map<String, JsonValue> =
    (field(name) as? JsonValue.Obj)?.fields ?: emptyMap()

fun JsonValue.arrayOrEmpty(name: String): List<JsonValue> =
    (field(name) as? JsonValue.Arr)?.items ?: emptyList()

fun JsonValue.str(name: String, def: String = ""): String =
    (field(name) as? JsonValue.Str)?.value ?: def

fun JsonValue.strOrNull(name: String): String? = (field(name) as? JsonValue.Str)?.value

fun JsonValue.int(name: String, def: Int = 0): Int =
    when (val v = field(name)) {
        is JsonValue.Num -> v.value.toInt()
        is JsonValue.Str -> v.value.toIntOrNull() ?: def
        else -> def
    }

fun JsonValue.long(name: String, def: Long = 0L): Long =
    when (val v = field(name)) {
        is JsonValue.Num -> v.value.toLong()
        is JsonValue.Str -> v.value.toLongOrNull() ?: def
        else -> def
    }

fun JsonValue.bool(name: String, def: Boolean = false): Boolean =
    when (val v = field(name)) {
        is JsonValue.Bool -> v.value
        is JsonValue.Str -> v.value.equals("true", ignoreCase = true)
        else -> def
    }

fun JsonValue.stringList(name: String): List<String> =
    arrayOrEmpty(name).mapNotNull { (it as? JsonValue.Str)?.value }

// ---------------------------------------------------------------- 构造器

fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj {
    val map = LinkedHashMap<String, JsonValue>(pairs.size)
    for ((key, value) in pairs) map[key] = value
    return JsonValue.Obj(map)
}

fun jsonObject(fields: Map<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(fields))

fun jsonArray(vararg items: JsonValue): JsonValue.Arr = JsonValue.Arr(items.toList())

fun jsonArray(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

/** 字符串转 JSON 值；null 变成 JSON null。 */
fun js(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

fun jn(value: Number): JsonValue = JsonValue.Num(value.toDouble())

fun jb(value: Boolean): JsonValue = JsonValue.Bool(value)

val jnull: JsonValue = JsonValue.Null

fun Iterable<String>.toJsonArray(): JsonValue.Arr = jsonArray(map { JsonValue.Str(it) })

// ---------------------------------------------------------------- 解析

class JsonParseException(message: String) : RuntimeException(message)

object Json {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("JSON 末尾存在多余内容（位置 ${parser.position}）")
        return value
    }

    /** 解析失败返回 null，供读取损坏/旧版本文件时降级。 */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (e: JsonParseException) {
        null
    }

    private class Parser(private val text: String) {
        var position = 0

        fun atEnd(): Boolean = position >= text.length

        private fun peek(): Char {
            if (atEnd()) throw JsonParseException("JSON 意外结束")
            return text[position]
        }

        private fun next(): Char {
            val c = peek()
            position++
            return c
        }

        fun skipWhitespace() {
            while (!atEnd()) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position++
                    else -> return
                }
            }
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("JSON 意外结束")
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> {
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else throw JsonParseException("JSON 非法字符 '$c'（位置 $position）")
                }
            }
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, position)) {
                throw JsonParseException("JSON 非法字面量（位置 $position）")
            }
            position += literal.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWhitespace()
                if (atEnd()) throw JsonParseException("JSON 对象未闭合")
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(fields)
                    else -> throw JsonParseException("JSON 对象缺少逗号（位置 $position）")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonParseException("JSON 数组未闭合")
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> throw JsonParseException("JSON 数组缺少逗号（位置 $position）")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("JSON 字符串未闭合")
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("JSON 字符串转义未结束")
                        when (val esc = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonParseException("JSON \\u 转义不完整")
                                val hex = text.substring(position, position + 4)
                                position += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("JSON \\u 转义非法: $hex")
                                sb.append(code.toChar())
                            }
                            else -> throw JsonParseException("JSON 未知转义 \\$esc")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonParseException("JSON 字符串含控制字符")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = position
            if (!atEnd() && peek() == '-') position++
            while (!atEnd() && text[position] in '0'..'9') position++
            if (!atEnd() && text[position] == '.') {
                position++
                while (!atEnd() && text[position] in '0'..'9') position++
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position++
                while (!atEnd() && text[position] in '0'..'9') position++
            }
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("JSON 非法数字: $raw")
            return JsonValue.Num(value)
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || next() != c) throw JsonParseException("JSON 期望 '$c'（位置 $position）")
        }
    }
}
