package com.zhaoge.miragedroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListParserTest {
    private val p65Sample = """
        {"InstalledApps":[
          {"package":"com.xiaomi.miwear.weather","appName":"天气","icon":"weather.png","hideFlag":false},
          {"package":"com.xiaomi.miwear.alarm","appName":"闹钟","icon":"alarm.png","hideFlag":true},
          {"package":"com.example.third","appName":"第三方表盘","icon":"x.png","hideFlag":false}
        ]}
    """.trimIndent()

    @Test
    fun parsesInstalledApps() {
        val list = AppListParser.parse(p65Sample)
        assertNotNull(list)
        assertEquals(3, list!!.count)
        assertEquals("天气", list.entries[0].name)
        assertEquals("com.xiaomi.miwear.weather", list.entries[0].packageName)
        assertEquals(false, list.entries[0].hidden)
        assertTrue(list.entries[1].hidden)
    }

    @Test
    fun toleratesConsoleNoiseAroundJson() {
        val noisy = "cat /data/quickapp/apps.json\n" +
            "InstalledApps count is: 2\n" +
            p65Sample +
            "\nap> "
        val list = AppListParser.parse(noisy)
        assertNotNull(list)
        assertEquals(3, list!!.count)
    }

    @Test
    fun acceptsTopLevelArray() {
        val raw = """[{"package":"com.x.a","name":"A"},{"package":"com.x.b","label":"B"}]"""
        val list = AppListParser.parse(raw)
        assertNotNull(list)
        assertEquals(listOf("A", "B"), list!!.entries.map { it.name })
    }

    @Test
    fun toleratesAlternativeFieldNames() {
        val raw = """{"installedApps":[{"pkg":"com.x.c","displayName":"C","hidden":true}]}"""
        val list = AppListParser.parse(raw)
        assertNotNull(list)
        assertEquals("com.x.c", list!!.entries[0].packageName)
        assertEquals("C", list.entries[0].name)
        assertTrue(list.entries[0].hidden)
    }

    @Test
    fun fallsBackToFirstArrayInObject() {
        val raw = """{"count":2,"apps":[{"package":"com.x.d","name":"D"}]}"""
        val list = AppListParser.parse(raw)
        assertNotNull(list)
        assertEquals(1, list!!.count)
    }

    @Test
    fun entriesWithoutNameUsePackageAsDisplayName() {
        val list = AppListParser.parse("""{"InstalledApps":[{"package":"com.x.e"}]}""")
        assertNotNull(list)
        assertEquals("com.x.e", list!!.entries[0].displayName)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(AppListParser.parse(""))
        assertNull(AppListParser.parse("ls: /data/quickapp/apps.json: No such file or directory"))
        assertNull(AppListParser.parse("{not json}"))
        assertNull(AppListParser.parse("ap> "))
    }

    @Test
    fun hidesEmptyEntries() {
        val list = AppListParser.parse("""{"InstalledApps":[{}, {"package":"com.x.f"}]}""")
        assertNotNull(list)
        assertEquals(1, list!!.count)
    }

    @Test
    fun extractJsonPicksBalancedPayload() {
        val text = "log line\n{\"a\":{\"b\":1}}\ntrailing"
        assertEquals("{\"a\":{\"b\":1}}", AppListParser.extractJson(text))
        assertNull(AppListParser.extractJson("no json here"))
    }
}
