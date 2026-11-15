package com.zhaoge.miragedroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelsTest {
    @Test
    fun virtualDeviceRoundTrips() {
        val device = VirtualDevice.create(DeviceProfiles.P65, "  我的手表  ", "fw-1").copy(
            batteryLevel = 42,
            batteryCharging = false,
            shell = true,
            bluetoothBridge = true,
            bluetoothPort = 49000,
            extraArguments = listOf("-d", "guest_errors"),
            lastBootedAt = 1763000000000L,
            storageBuiltAt = 1763000000001L,
        )
        val decoded = VirtualDevice.fromJson(Json.parse(device.toJson().encode()))
        assertNotNull(decoded)
        assertEquals(device, decoded)
        assertEquals("我的手表", device.name)
    }

    @Test
    fun virtualDeviceToleratesMissingFields() {
        val decoded = VirtualDevice.fromJson(Json.parse("""{"id":"d1","profileId":"p65"}"""))
        assertNotNull(decoded)
        assertEquals("p65", decoded!!.name)
        assertEquals(VirtualDevice.DEFAULT_BATTERY, decoded.batteryLevel)
        assertTrue(decoded.batteryCharging)
        assertTrue(decoded.extraArguments.isEmpty())
        assertNull(decoded.firmwareId)
    }

    @Test
    fun invalidDeviceJsonReturnsNull() {
        assertNull(VirtualDevice.fromJson(Json.parse("""{"profileId":"p65"}""")))
        assertNull(VirtualDevice.fromJson(Json.parse("""{"id":"d1"}""")))
        assertNull(VirtualDevice.fromJson(Json.parse("[]")))
    }

    @Test
    fun macAddressIsLocallyAdministered() {
        val address = VirtualDevice.randomBluetoothAddress()
        assertTrue("MAC 格式: $address", Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(address))
        val firstByte = address.substring(0, 2).toInt(16)
        assertEquals("本地管理位应为 1", 0x02, firstByte and 0x02)
        assertEquals("组播位应为 0", 0, firstByte and 0x01)
        assertTrue("两次生成不应相同", address != VirtualDevice.randomBluetoothAddress())
    }

    @Test
    fun firmwareRecordRoundTrips() {
        val firmware = FirmwareRecord(
            id = "fw-1",
            displayName = "REDMI Watch 6 v3.100.043",
            product = "miwear.watch.p65",
            version = "3.100.043",
            chip = "best1502p",
            importedAt = 1763000000000L,
            sourceName = "ota.bin",
            apFile = "vela_ap.bin",
            resourceFile = "vela_resource.bin",
            sizeBytes = 123456789L,
            stage = 2,
        )
        val decoded = FirmwareRecord.fromJson(Json.parse(firmware.toJson().encode()))
        assertEquals(firmware, decoded)
        assertTrue(decoded!!.hasAp)
    }

    @Test
    fun settingsRoundTripAndDefaults() {
        assertEquals(AppSettings.DEFAULTS, AppSettings.fromJson(Json.parse(AppSettings.DEFAULTS.toJson().encode())))
        val custom = AppSettings(
            lastDeviceId = "dev-1",
            keepScreenOn = false,
            shellByDefault = true,
            autoStartSession = false,
            qemuPath = "/data/local/tmp/qemu-system-arm",
            firmwareDirectory = "/sdcard/Mirage/firmware",
        )
        assertEquals(custom, AppSettings.fromJson(Json.parse(custom.toJson().encode())))
        assertEquals(AppSettings.DEFAULTS, AppSettings.fromJson(Json.parse("{}")))
    }

    @Test
    fun firmwareInfoParsing() {
        val text = """{"product":"miwear.watch.p65","chip":"best1502p","version":"3.100.043","flashSize":16777216,"products":["miwear.watch.p65"]}"""
        val info = FirmwareInfo.parse(text)
        assertNotNull(info)
        assertEquals("miwear.watch.p65", info!!.product)
        assertEquals(16777216L, info.flashSize)
        assertEquals(listOf("miwear.watch.p65"), info.products)
        assertNull(FirmwareInfo.parse("{}"))
        assertNull(FirmwareInfo.parse("not json"))
    }

    @Test
    fun formatBytesIsReadable() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 + 512 * 1024))
        assertEquals("528.0 MB", formatBytes(553_648_128L))
        assertFalse(formatBytes(0).isEmpty())
    }
}
