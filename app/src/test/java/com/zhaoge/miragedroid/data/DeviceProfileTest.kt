package com.zhaoge.miragedroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun p65MatchesDocs() {
        val p65 = DeviceProfiles.P65
        assertEquals(432, p65.screen.width)
        assertEquals(514, p65.screen.height)
        assertEquals(ScreenShape.ROUNDED_RECT, p65.screen.shape)
        assertEquals(48, p65.screen.cornerRadius)
        assertEquals(1, p65.consoleUart)
        assertEquals("p65", p65.qemuBoard)
        assertEquals(listOf("home", "pgdn"), p65.buttons.map { it.qcode })
        assertEquals(listOf("nor.img", "nand.img"), p65.storage.imageFiles)
        assertTrue(p65.runsProduct("miwear.watch.p65"))
        assertTrue("产品名带地区后缀也要匹配", p65.runsProduct("miwear.watch.p65cn"))
        assertFalse(p65.runsProduct("miwear.watch.q66"))
    }

    @Test
    fun q66MatchesDocs() {
        val q66 = DeviceProfiles.Q66
        assertEquals(212, q66.screen.width)
        assertEquals(520, q66.screen.height)
        assertEquals(104, q66.screen.cornerRadius)
        assertEquals(2, q66.consoleUart)
        assertTrue("手环没有物理按键", q66.buttons.isEmpty())
        assertEquals(listOf("nor.img", "nand.img"), q66.storage.imageFiles)
        assertTrue(q66.runsProduct("miwear.watch.q66cn"))
    }

    @Test
    fun emmcProfileNeedsEmmcImage() {
        val q63 = DeviceProfiles.Q63
        assertFalse(q63.storage.mainNor)
        assertTrue(q63.storage.imageFiles.contains("emmc.img"))
        assertTrue(q63.storage.imageFiles.contains("sensor-nor.img"))
        assertFalse(q63.storage.imageFiles.contains("nor.img"))
        assertEquals(ScreenShape.CIRCLE, q63.screen.shape)
        assertEquals(466, q63.screen.width)
    }

    @Test
    fun builtinsStartWithDeliveryTargets() {
        assertEquals("p65", DeviceProfiles.builtins[0].id)
        assertEquals("q66", DeviceProfiles.builtins[1].id)
        assertEquals(8, DeviceProfiles.builtins.size)
        assertEquals(8, DeviceProfiles.builtins.map { it.id }.toSet().size)
    }

    @Test
    fun lookupsAreSafe() {
        assertEquals(DeviceProfiles.P65, DeviceProfiles.byId("p65"))
        assertNull(DeviceProfiles.byId("nope"))
        val fallback = DeviceProfiles.require("nope")
        assertEquals("nope", fallback.id)
        assertTrue(fallback.name.contains("未知机型"))
        assertTrue(fallback.notes.isNotEmpty())
    }

    @Test
    fun profileSerializes() {
        val encoded = DeviceProfiles.P65.toJson().encode()
        val decoded = Json.parse(encoded)
        assertEquals("p65", decoded.str("id"))
        assertEquals(432, decoded.field("screen")!!.int("width"))
        assertEquals(2, decoded.arrayOrEmpty("buttons").size)
        assertEquals("home", decoded.arrayOrEmpty("buttons")[0].str("qcode"))
        assertEquals("#FF5722", decoded.str("accentColor"))
    }

    @Test
    fun storageKindTitles() {
        assertEquals("SPI NAND", DeviceProfiles.P65.storage.kindTitle)
        assertEquals("eMMC", DeviceProfiles.Q63.storage.kindTitle)
    }
}
