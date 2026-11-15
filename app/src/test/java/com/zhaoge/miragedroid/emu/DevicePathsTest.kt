package com.zhaoge.miragedroid.emu

import com.zhaoge.miragedroid.data.DeviceProfiles
import com.zhaoge.miragedroid.data.DeviceStore
import com.zhaoge.miragedroid.data.VirtualDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevicePathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): DeviceStore = DeviceStore(temporaryFolder.newFolder("store"))

    @Test
    fun layoutMatchesMirageNames() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        assertEquals("nor.img", paths.nor.name)
        assertEquals("sensor-nor.img", paths.sensorNor.name)
        assertEquals("nand.img", paths.nand.name)
        assertEquals("emmc.img", paths.emmc.name)
        assertEquals("qmp.sock", paths.qmpSocket.name)
        assertEquals("vnc.sock", paths.vncSocket.name)
        assertEquals("console.sock", paths.consoleSocket.name)
        assertEquals("gdb.sock", paths.gdbSocket.name)
        assertEquals(File(paths.deviceDir, "Logs"), paths.logs)
        assertEquals("uart1.log", paths.uartLog(1).name)
    }

    @Test
    fun shortRuntimePathKeepsUnixSocketLimit() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        // 常见 Android 路径前缀 ~55 字节，必须留足 104 字节的余量
        assertTrue(paths.shortId.length <= 10)
        assertTrue(paths.qmpSocket.absolutePath.length < 100)
    }

    @Test
    fun prepareCreatesDirsAndCleansStaleSockets() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        assertFalse(paths.deviceDir.exists())
        paths.prepare()
        assertTrue(paths.deviceDir.isDirectory)
        assertTrue(paths.logs.isDirectory)
        assertTrue(paths.screenshots.isDirectory)
        assertTrue(paths.runtimeDir.isDirectory)

        paths.vncSocket.writeText("stale")
        paths.pidFile.writeText("123")
        paths.prepare()
        assertFalse("旧 socket 必须清掉", paths.vncSocket.exists())
        assertFalse("旧 pid 文件必须清掉", paths.pidFile.exists())
    }

    @Test
    fun missingImagesTracksProfileRequirements() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        paths.prepare()
        assertEquals(listOf("nor.img", "nand.img"), paths.missingImages(DeviceProfiles.P65))
        paths.nor.writeText("x")
        paths.nand.writeText("x")
        assertTrue(paths.missingImages(DeviceProfiles.P65).isEmpty())
    }

    @Test
    fun logsCollectionIncludesUartAndStdout() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.Q66, "手环", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        paths.prepare()
        paths.uartLog(0).writeText("a")
        paths.qemuLog.writeText("b")
        paths.stdoutLog.writeText("c")
        val names = paths.allLogs().map { it.name }.toSet()
        assertTrue(names.contains("uart0.log"))
        assertTrue(names.contains("qemu.log"))
        assertTrue(names.contains("stdout.log"))
    }

    @Test
    fun firmwareImagesResolveInFirmwareDir() {
        val store = store()
        store.ensureDirectories()
        val device = VirtualDevice.create(DeviceProfiles.P65, "手表", null)
        val paths = DevicePaths(store, device.id, temporaryFolder.newFolder("cache"))
        val firmwareDir = temporaryFolder.newFolder("fw")
        assertEquals(File(firmwareDir, "vela_ap.bin"), paths.firmwareAp(firmwareDir))
        assertEquals(File(firmwareDir, "vela_resource.bin"), paths.firmwareResource(firmwareDir))
    }
}
