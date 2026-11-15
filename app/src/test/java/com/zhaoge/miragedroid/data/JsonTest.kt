package com.zhaoge.miragedroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun parsesScalars() {
        assertEquals(JsonValue.Bool(true), Json.parse("true"))
        assertEquals(JsonValue.Bool(false), Json.parse("false"))
        assertEquals(JsonValue.Null, Json.parse("null"))
        assertEquals(JsonValue.Str("hi"), Json.parse("\"hi\""))
        assertEquals(JsonValue.Num(42.0), Json.parse("42"))
        assertEquals(JsonValue.Num(-3.5), Json.parse("-3.5"))
        assertEquals(JsonValue.Num(1.25e3), Json.parse("1.25e3"))
    }

    @Test
    fun parsesNestedStructures() {
        val value = Json.parse("""{"a":[1,2,{"b":"c"}],"d":{"e":null}}""")
        assertTrue(value is JsonValue.Obj)
        val array = value.field("a") as JsonValue.Arr
        assertEquals(3, array.items.size)
        assertEquals(2.0, (array.items[1] as JsonValue.Num).value, 0.0)
        assertEquals("c", ((array.items[2] as JsonValue.Obj).field("b") as JsonValue.Str).value)
        assertEquals(JsonValue.Null, (value.field("d") as JsonValue.Obj).field("e"))
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val value = Json.parse("\"a\\\"b\\\\c\\nd\\te\\u4E2D\"")
        assertEquals("a\"b\\c\nd\te中", (value as JsonValue.Str).value)
    }

    @Test
    fun roundTripsThroughEncode() {
        val source = jsonObject(
            "name" to js("REDMI Watch 6"),
            "level" to jn(80),
            "charging" to jb(true),
            "tags" to jsonArray(js("a"), js("b")),
            "nothing" to jnull,
            "nested" to jsonObject("width" to jn(432), "ratio" to jn(0.5)),
        )
        val decoded = Json.parse(source.encode())
        assertEquals(source, decoded)
    }

    @Test
    fun encodesLargeLongsWithoutScientificNotation() {
        val now = 1763000000000L
        val encoded = jsonObject("t" to jn(now)).encode()
        assertTrue("应写整数而不是科学计数法: $encoded", encoded.contains("1763000000000"))
        assertEquals(now, Json.parse(encoded).long("t"))
    }

    @Test
    fun encodesControlCharacters() {
        val encoded = jsonObject("s" to js("a\u0001b")).encode()
        assertTrue(encoded.contains("\\u0001"))
        assertEquals("a\u0001b", Json.parse(encoded).str("s"))
    }

    @Test
    fun malformedJsonReturnsNullFromParseOrNull() {
        assertNull(Json.parseOrNull(""))
        assertNull(Json.parseOrNull("{"))
        assertNull(Json.parseOrNull("[1,]"))
        assertNull(Json.parseOrNull("{\"a\":1} trailing"))
        assertNull(Json.parseOrNull("nope"))
    }

    @Test
    fun accessorsFallBackToDefaults() {
        val value = Json.parse("""{"a":"x","b":7,"c":true,"list":["p","q"],"obj":{"k":1}}""")
        assertEquals("x", value.str("a"))
        assertEquals("默认", value.str("missing", "默认"))
        assertEquals(7, value.int("b"))
        assertEquals(9, value.int("missing", 9))
        assertTrue(value.bool("c"))
        assertEquals(listOf("p", "q"), value.stringList("list"))
        assertEquals(1, value.objOrEmpty("obj").size)
        assertNull(value.strOrNull("missing"))
    }
}
