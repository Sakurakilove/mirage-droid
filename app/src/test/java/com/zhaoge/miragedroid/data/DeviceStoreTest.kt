package com.zhaoge.miragedroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): DeviceStore = DeviceStore(temporaryFolder.newFolder("store"))

    @Test
    fun createsDirectories() {
        val store = store()
        store.ensureDirectories()
        assertTrue(store.root.isDirectory)
        assertTrue(store.devicesRoot.isDirectory)
        assertTrue(store.firmwaresRoot.isDirectory)
        assertTrue(store.exportsRoot.isDirectory)
    }

    @Test
    fun devicesRoundTrip() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "我的手表", "fw-1")
        store.upsertDevice(device)
        val loaded = store.device(device.id)
        assertEquals(device, loaded)
        assertEquals(1, store.devices().size)
    }

    @Test
    fun upsertReplacesExistingDevice() {
        val store = store()
        val device = VirtualDevice.create(DeviceProfiles.P65, "旧名字", null)
        store.upsertDevice(device)
        store.upsertDevice(device.copy(name = "新名字", batteryLevel = 30))
        assertEquals(1, store.devices().size)
        assertEquals("新名字", store.device(device.id)!!.name)
        assertEquals(30, store.device(device.id)!!.batteryLevel)
    }

    @Test
    fun deleteDeviceRemovesRecordAndFiles() {
        val store = store()
        val device = VirtualDevice.create(DeviceProfiles.P65, "临时设备", null)
        store.upsertDevice(device)
        val dir = store.deviceDir(device.id)
        dir.mkdirs()
        File(dir, "nor.img").writeText("x")
        assertTrue(store.deleteDevice(device.id))
        assertTrue(store.devices().isEmpty())
        assertFalse(dir.exists())
        assertFalse(store.deleteDevice("not-there"))
    }

    @Test
    fun deleteFirmwareUnbindsDevices() {
        val store = store()
        val firmware = sampleFirmware("fw-1")
        store.upsertFirmware(firmware)
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", "fw-1").copy(storageBuiltAt = 123L)
        store.upsertDevice(device)
        store.deleteFirmware("fw-1")
        assertTrue(store.firmwares().isEmpty())
        val reloaded = store.device(device.id)!!
        assertNull(reloaded.firmwareId)
        assertEquals(0L, reloaded.storageBuiltAt)
    }

    @Test
    fun settingsRoundTrip() {
        val store = store()
        assertEquals(AppSettings.DEFAULTS, store.settings())
        val updated = store.updateSettings { it.copy(keepScreenOn = false, lastDeviceId = "d1") }
        assertFalse(updated.keepScreenOn)
        val reloaded = store.settings()
        assertFalse(reloaded.keepScreenOn)
        assertEquals("d1", reloaded.lastDeviceId)
    }

    @Test
    fun corruptedJsonDegradesToEmpty() {
        val store = store()
        store.ensureDirectories()
        File(store.root, "devices.json").writeText("{ this is not json")
        File(store.root, "firmware.json").writeText("\u0000\u0001")
        File(store.root, "settings.json").writeText("[]")
        assertTrue(store.devices().isEmpty())
        assertTrue(store.firmwares().isEmpty())
        assertEquals(AppSettings.DEFAULTS, store.settings())
        // 坏文件仍能被打扫干净：下一次写入必须成功
        store.upsertDevice(VirtualDevice.create(DeviceProfiles.Q66, "手环", null))
        assertEquals(1, store.devices().size)
    }

    @Test
    fun atomicWriteLeavesNoTempFile() {
        val store = store()
        store.upsertDevice(VirtualDevice.create(DeviceProfiles.P65, "手表", null))
        assertFalse(File(store.root, "devices.json.tmp").exists())
        assertTrue(File(store.root, "devices.json").isFile)
    }

    @Test
    fun multipleDevicesAndFirmwaresPersist() {
        val store = store()
        val p65 = VirtualDevice.create(DeviceProfiles.P65, "手表 6", null)
        val q66 = VirtualDevice.create(DeviceProfiles.Q66, "手环 11", null)
        store.upsertDevice(p65)
        store.upsertDevice(q66)
        store.upsertFirmware(sampleFirmware("fw-1"))
        store.upsertFirmware(sampleFirmware("fw-2"))
        val ids = store.devices().map { it.id }.toSet()
        assertEquals(setOf(p65.id, q66.id), ids)
        assertEquals(2, store.firmwares().size)
    }

    private fun sampleFirmware(id: String) = FirmwareRecord(
        id = id,
        displayName = "固件 $id",
        product = "miwear.watch.p65",
        version = "3.100.043",
        chip = "best1502p",
        importedAt = 1763000000000L,
        sourceName = "$id.bin",
        apFile = "vela_ap.bin",
        resourceFile = "vela_resource.bin",
        sizeBytes = 1024,
        stage = 2,
    )
}
