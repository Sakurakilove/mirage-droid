package com.zhaoge.miragedroid.emu

import com.zhaoge.miragedroid.data.DeviceProfiles
import com.zhaoge.miragedroid.data.DeviceStore
import com.zhaoge.miragedroid.data.VirtualDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: DeviceStore
    private lateinit var cacheDir: File
    private lateinit var firmwareDir: File
    private lateinit var device: VirtualDevice

    @Before
    fun setUp() {
        store = DeviceStore(temporaryFolder.newFolder("store"))
        store.ensureDirectories()
        cacheDir = temporaryFolder.newFolder("cache")
        firmwareDir = temporaryFolder.newFolder("firmware")
        File(firmwareDir, "vela_ap.bin").writeText("ap")
        device = VirtualDevice.create(DeviceProfiles.P65, "REDMI Watch 6", "fw-1")
            .copy(bluetoothAddress = "AA:BB:CC:DD:EE:FF")
        store.upsertDevice(device)
    }

    private fun plan(profileId: String, configure: (VirtualDevice) -> VirtualDevice = { it }): LaunchPlan {
        val profile = DeviceProfiles.require(profileId)
        val configured = configure(device)
        val paths = DevicePaths(store, configured.id, cacheDir)
        paths.prepare()
        return LaunchPlanBuilder.build(
            profile = profile,
            device = configured,
            paths = paths,
            firmwareDir = firmwareDir,
            executable = "/data/app/lib/arm64/libqemu-system-arm.so",
        )
    }

    @Test
    fun buildsP65MachineAndEndpoints() {
        val paths = DevicePaths(store, device.id, cacheDir)
        paths.prepare()
        val plan = plan("p65")
        val args = plan.arguments
        val machine = args[args.indexOf("-M") + 1]
        assertEquals(
            "miwear,profile=p65,flash-image=${paths.nor.absolutePath}," +
                "nand-image=${paths.nand.absolutePath},battery-level=80,battery-charging=on",
            machine,
        )
        assertTrue(args.contains("none")) // -display none
        assertEquals(File(firmwareDir, "vela_ap.bin").absolutePath, args[args.indexOf("-kernel") + 1])
        assertEquals(File(firmwareDir, "vela_ap.bin").absolutePath, plan.firmwareImage.absolutePath)
        assertEquals("unix:" + paths.vncSocket.absolutePath, args[args.indexOf("-vnc") + 1])
        assertEquals(
            "unix:" + paths.qmpSocket.absolutePath + ",server=on,wait=off",
            args[args.indexOf("-qmp") + 1],
        )
        // 第二个 QMP 监视器（qmp-aux.sock），给外部工具用
        val qmpArgs = ArrayList<String>()
        for (index in args.indices) {
            if (args[index] == "-qmp") qmpArgs.add(args[index + 1])
        }
        assertEquals(2, qmpArgs.size)
        assertEquals("unix:" + paths.qmpAuxSocket.absolutePath + ",server=on,wait=off", qmpArgs[1])
        assertEquals(paths.runtimeDir, plan.workingDirectory)
        assertEquals("none", args[args.indexOf("-monitor") + 1])
        assertTrue(args.contains("MirageDroid: REDMI Watch 6"))
        assertEquals("driver=bes.btss,property=bdaddr,value=AA:BB:CC:DD:EE:FF", args[args.indexOf("-global") + 1])
        assertEquals(paths.qemuLog.absolutePath, args[args.indexOf("-D") + 1])
    }

    @Test
    fun serialMappingFollowsConsoleUart() {
        // p65: console 在 UART1
        val p65 = plan("p65")
        val p65Serials = serials(p65)
        assertEquals(listOf("file:" + cacheSerialPath(p65, 0), "chardev:con", "file:" + cacheSerialPath(p65, 2)), p65Serials)

        // q66: console 在 UART2
        store.upsertDevice(device.copy(profileId = "q66"))
        val q66 = plan("q66")
        val q66Serials = serials(q66)
        assertEquals("chardev:con", q66Serials[2])
        assertTrue(q66Serials[0].startsWith("file:"))
    }

    private fun serials(plan: LaunchPlan): List<String> {
        val out = ArrayList<String>()
        for (index in plan.arguments.indices) {
            if (plan.arguments[index] == "-serial") out.add(plan.arguments[index + 1])
        }
        return out
    }

    private fun cacheSerialPath(plan: LaunchPlan, uart: Int): String {
        val logs = File(store.deviceDir(device.id), "Logs")
        return File(logs, "uart$uart.log").absolutePath
    }

    @Test
    fun batteryOptionsFollowDeviceSettings() {
        val configured = device.copy(batteryLevel = 150, batteryCharging = false)
        store.upsertDevice(configured)
        val machine = plan("p65") { it.copy(batteryLevel = 150, batteryCharging = false) }
            .arguments.let { it[it.indexOf("-M") + 1] }
        assertTrue("电量必须收敛到 0..100: $machine", machine.contains("battery-level=100"))
        assertTrue(machine.contains("battery-charging=off"))
    }

    @Test
    fun shellAddsGdbStub() {
        val plan = plan("p65") { it.copy(shell = true) }
        assertTrue(plan.arguments.contains("-S"))
        val gdb = plan.arguments[plan.arguments.indexOf("-gdb") + 1]
        assertTrue(gdb.startsWith("unix:"))
        assertTrue(gdb.endsWith(",server=on,wait=off"))
        assertTrue(gdb.contains("gdb.sock"))
        assertFalse(plan.arguments.contains("qemu_cmdline"))
    }

    @Test
    fun bluetoothBridgeAddsChardev() {
        val plan = plan("p65") { it.copy(bluetoothBridge = true, bluetoothPort = 49000) }
        val chardevs = ArrayList<String>()
        for (index in plan.arguments.indices) {
            if (plan.arguments[index] == "-chardev") chardevs.add(plan.arguments[index + 1])
        }
        assertTrue(chardevs.any { it.startsWith("socket,id=con,") })
        assertTrue(chardevs.any { it.contains("id=bt") && it.contains("port=49000") })
        assertTrue(plan.arguments.contains("driver=bes.btss,property=chardev,value=bt"))
    }

    @Test
    fun emmcProfileAddsDrive() {
        store.upsertDevice(device.copy(profileId = "q63", bluetoothAddress = "AA:BB:CC:DD:EE:FF"))
        val plan = plan("q63")
        val drive = plan.arguments[plan.arguments.indexOf("-drive") + 1]
        assertTrue(drive.startsWith("if=sd,index=0,format=raw,file="))
        assertTrue(drive.endsWith("emmc.img"))
        val machine = plan.arguments[plan.arguments.indexOf("-M") + 1]
        assertFalse("eMMC 机型没有 flash-image", machine.contains("flash-image="))
        assertTrue(machine.contains("sensor-nor="))
    }

    @Test
    fun extraArgumentsPassedThrough() {
        val plan = plan("p65") { it.copy(extraArguments = listOf("-d", "guest_errors")) }
        assertTrue(plan.arguments.contains("-d"))
        assertTrue(plan.arguments.contains("guest_errors"))
    }

    @Test
    fun keymapsDirectoryOnlyWhenPresent() {
        val keymaps = temporaryFolder.newFolder("qemu-data")
        val planWithout = plan("p65")
        assertFalse(planWithout.arguments.contains("-L"))

        val paths = DevicePaths(store, device.id, cacheDir)
        paths.prepare()
        File(keymaps, "keymaps").mkdirs()
        File(keymaps, "keymaps/en-us").writeText("x")
        val planWith = LaunchPlanBuilder.build(
            DeviceProfiles.P65, device, paths, firmwareDir,
            executable = "qemu-system-arm", keymapsDir = keymaps,
        )
        assertEquals(keymaps.absolutePath, planWith.arguments[planWith.arguments.indexOf("-L") + 1])
    }

    @Test
    fun commandLineQuotesSafely() {
        assertEquals("plain/path:1", LaunchPlan.quote("plain/path:1"))
        assertEquals("'带 空格'", LaunchPlan.quote("带 空格"))
        assertEquals("'a'\\''b'", LaunchPlan.quote("a'b"))
        val plan = plan("p65")
        assertTrue(plan.commandLine().startsWith("/data/app/lib/arm64/libqemu-system-arm.so -M miwear,profile=p65"))
    }

    @Test
    fun escapesCommasForQemuOptions() {
        assertEquals("a,,b", LaunchPlanBuilder.escapeCommas("a,b"))
        assertEquals("noComma", LaunchPlanBuilder.escapeCommas("noComma"))
    }
}
