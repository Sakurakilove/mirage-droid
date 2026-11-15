package com.zhaoge.miragedroid.net

import com.zhaoge.miragedroid.data.Json
import com.zhaoge.miragedroid.data.JsonValue
import com.zhaoge.miragedroid.data.arrayOrEmpty
import com.zhaoge.miragedroid.data.field
import com.zhaoge.miragedroid.data.int
import com.zhaoge.miragedroid.data.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmpCodecTest {
    @Test
    fun parsesGreeting() {
        val message = QmpProtocol.parseLine("""{"QMP":{"version":{"qemu":{"major":9,"minor":2}},"capabilities":[]}}""")
        assertTrue(message is QmpMessage.Greeting)
        val payload = (message as QmpMessage.Greeting).payload
        assertNotNull(payload)
        assertEquals(9, payload!!.field("version")!!.field("qemu")!!.int("major"))
    }

    @Test
    fun parsesSuccessResponse() {
        val message = QmpProtocol.parseLine("""{"return":{"running":true,"status":"running"},"id":2}""")
        assertTrue(message is QmpMessage.Response)
        val response = message as QmpMessage.Response
        assertEquals(JsonValue.Num(2.0), response.id)
        assertNull(response.error)
        assertEquals(true, response.result!!.field("running")!!.let { (it as JsonValue.Bool).value })
        assertEquals("running", response.result!!.str("status"))
    }

    @Test
    fun parsesErrorResponse() {
        val message = QmpProtocol.parseLine("""{"error":{"class":"GenericError","desc":"invalid parameter"},"id":7}""")
        val response = message as QmpMessage.Response
        assertNotNull(response.error)
        assertEquals("GenericError", response.error!!.klass)
        assertEquals("invalid parameter", response.error!!.desc)
        assertNull(response.result)
    }

    @Test
    fun parsesEvent() {
        val message = QmpProtocol.parseLine("""{"event":"SHUTDOWN","data":{"guest":true},"timestamp":{"seconds":1}}""")
        assertTrue(message is QmpMessage.Event)
        val event = message as QmpMessage.Event
        assertEquals("SHUTDOWN", event.name)
        assertEquals("SHUTDOWN", event.name)
        assertTrue(event.data != null)
    }

    @Test
    fun malformedLinesAreReportedNotThrown() {
        assertTrue(QmpProtocol.parseLine("not json") is QmpMessage.Malformed)
        assertTrue(QmpProtocol.parseLine("") is QmpMessage.Malformed)
        assertTrue(QmpProtocol.parseLine("123") is QmpMessage.Malformed)
    }

    @Test
    fun encodesCapabilitiesCommand() {
        val line = QmpProtocol.encode(QmpCommands.capabilities(), 1)
        assertEquals("""{"execute":"qmp_capabilities","id":1}""", line)
    }

    @Test
    fun encodesCommandWithArguments() {
        val line = QmpProtocol.encode(QmpCommands.screendump("/tmp/a.png"), 5)
        val decoded = Json.parse(line)
        assertEquals("screendump", decoded.str("execute"))
        assertEquals(5, decoded.int("id"))
        assertEquals("png", decoded.field("arguments")!!.str("format"))
        assertEquals("/tmp/a.png", decoded.field("arguments")!!.str("filename"))
        assertTrue(QmpProtocol.encodeLine(QmpCommands.quit(), 9).last() == '\n'.code.toByte())
    }

    @Test
    fun encodesKeyEvent() {
        val command = QmpCommands.inputKey("home", down = true)
        assertEquals("input-send-event", command.name)
        val events = command.arguments!!.arrayOrEmpty("events")
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("key", event.str("type"))
        val data = event.field("data")!!
        assertEquals(true, (data.field("down") as JsonValue.Bool).value)
        val key = data.field("key")!!
        assertEquals("qcode", key.str("type"))
        assertEquals("home", key.str("data"))
    }

    @Test
    fun encodesTouchEvents() {
        val command = QmpCommands.inputTouch(0.5, 0.25, down = true)
        val events = command.arguments!!.arrayOrEmpty("events")
        assertEquals(3, events.size)
        assertEquals("abs", events[0].str("type"))
        assertEquals("x", events[0].field("data")!!.str("axis"))
        assertEquals(16383, events[0].field("data")!!.int("value"))
        assertEquals(8191, events[1].field("data")!!.int("value"))
        assertEquals("btn", events[2].str("type"))
        assertEquals(true, (events[2].field("data")!!.field("down") as JsonValue.Bool).value)

        val moveOnly = QmpCommands.inputTouch(1.0, 1.0, null)
        assertEquals(2, moveOnly.arguments!!.arrayOrEmpty("events").size)
        assertEquals(32767, moveOnly.arguments!!.arrayOrEmpty("events")[0].field("data")!!.int("value"))
    }

    @Test
    fun qomCommands() {
        val get = QmpCommands.qomGet("/machine", "battery-level")
        assertEquals("qom-get", get.name)
        assertEquals("/machine", get.arguments!!.str("path"))
        val set = QmpCommands.qomSet("/machine", "battery-level", JsonValue.Num(55.0))
        assertEquals("qom-set", set.name)
        assertEquals(55.0, (set.arguments!!.field("value") as JsonValue.Num).value, 0.0)
    }

    @Test
    fun responseHelpers() {
        assertEquals(80, QmpProtocol.responseInt(QmpProtocol.parseLine("""{"return":80,"id":1}""")))
        assertNull(QmpProtocol.responseInt(QmpProtocol.parseLine("""{"return":"x","id":1}""")))
        assertEquals(true, QmpProtocol.responseBool(QmpProtocol.parseLine("""{"return":true,"id":1}""")))
        assertEquals("ok", QmpProtocol.responseString(QmpProtocol.parseLine("""{"return":"ok","id":1}""")))
    }

    @Test
    fun lineReaderSplitsFragments() {
        val reader = QmpLineReader()
        assertTrue(reader.feed("""{"QMP":{""".toByteArray(Charsets.UTF_8)).isEmpty())
        val first = reader.feed("""}}""".toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte()))
        assertEquals(1, first.size)
        assertEquals("""{"QMP":{}}""", first[0])
    }

    @Test
    fun lineReaderHandlesMultipleLinesAndCrLf() {
        val reader = QmpLineReader()
        val payload = "{\"return\":1,\"id\":1}\r\n{\"event\":\"RESET\"}\n".toByteArray(Charsets.UTF_8)
        val lines = reader.feed(payload)
        assertEquals(2, lines.size)
        assertEquals("""{"return":1,"id":1}""", lines[0])
        assertEquals("""{"event":"RESET"}""", lines[1])
        assertFalse(lines[0].endsWith("\r"))
    }

    @Test
    fun lineReaderHandlesSplitInsideLine() {
        val reader = QmpLineReader()
        assertTrue(reader.feed("{\"ret".toByteArray(Charsets.UTF_8)).isEmpty())
        assertTrue(reader.feed("urn\":{}".toByteArray(Charsets.UTF_8)).isEmpty())
        val lines = reader.feed("\n".toByteArray(Charsets.UTF_8))
        assertEquals(1, lines.size)
        assertEquals("""{"return":{}""", lines[0])
    }
}
