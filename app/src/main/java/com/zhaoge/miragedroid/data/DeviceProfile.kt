package com.zhaoge.miragedroid.data

/**
 * 设备型号（profile）：描述一块被模拟的硬件 —— 跑在哪个 qemu-miwear board 上、
 * 屏幕与按键长什么样、存储如何布局。字段与 Mirage 的 DeviceProfile / StorageLayout
 * 对齐，方便和 devicelib / 文档互相印证。
 *
 * 内置 profile 与 qemu-miwear 的 bes_board.c 一一对应；p65（REDMI Watch 6）与
 * q66（小米手环 11）是首批实测目标，其余为预留（同一 board 家族）。
 */
enum class DeviceFamily(val title: String) {
    WATCH("手表"),
    BAND("手环"),
}

enum class ScreenShape { ROUNDED_RECT, CIRCLE }

data class ScreenSpec(
    val width: Int,
    val height: Int,
    val shape: ScreenShape,
    /** 面板像素单位的圆角半径（ROUNDED_RECT 时生效）。 */
    val cornerRadius: Int,
) {
    val aspect: Float get() = width.toFloat() / height.toFloat()
}

enum class ButtonPlacement { CROWN, RIGHT_UPPER, RIGHT_LOWER, LEFT }

/** 物理按键：qcode 是 QEMU 的键码（-global bes.gpio-keys / input-send-event）。 */
data class HardwareButton(
    val id: String,
    val title: String,
    val qcode: String,
    val placement: ButtonPlacement,
)

/** SPI NAND 布局（分页参数 + resource romfs 与 /data 区位置）。 */
data class NandSpec(
    val pageSize: Int,
    val oobSize: Int,
    val pagesPerBlock: Int,
    val blocks: Int,
    val resourceImage: String,
    val resourceFirstPage: Int,
    val resourcePages: Int,
    val systemPages: Int?,
    val dataPages: Int?,
    val dataStartPage: Int?,
) {
    val capacityBytes: Long
        get() = pageSize.toLong() * pagesPerBlock * blocks

    val imageBytes: Long
        get() = (pageSize + oobSize).toLong() * pagesPerBlock * blocks
}

data class EmmcSpec(
    val sizeGiB: Int,
    /** 由 vela_<name>.bin 填充的 romfs 分区。 */
    val romfs: List<String>,
)

enum class FactoryFormat(val title: String) {
    LITTLEFS("littlefs"),
    RAW_INFO("raw info"),
    P65_RECORD("p65 record"),
}

/** 工厂分区：/factory.prop 等设备身份数据的落盘位置。 */
data class FactoryPartition(
    val mainNor: Boolean,
    val offset: Long,
    val blockSize: Int,
    val blockCount: Int,
    val format: FactoryFormat,
)

data class StorageLayout(
    val mainNor: Boolean,
    val sensorNorSize: Long?,
    val nand: NandSpec?,
    val emmc: EmmcSpec?,
    val factory: FactoryPartition,
) {
    val kindTitle: String
        get() = when {
            nand != null -> "SPI NAND"
            emmc != null -> "eMMC"
            else -> "仅 NOR"
        }

    /** 本设备需要哪些镜像文件（与 devicelib buildStorage 产物一致）。 */
    val imageFiles: List<String>
        get() {
            val out = ArrayList<String>(4)
            if (mainNor) out.add("nor.img")
            if (sensorNorSize != null) out.add("sensor-nor.img")
            if (nand != null) out.add("nand.img")
            if (emmc != null) out.add("emmc.img")
            return out
        }
}

data class DeviceProfile(
    val id: String,
    val name: String,
    val family: DeviceFamily,
    /** 固件 boot info 里的 SoC 名字（CHIP=）。 */
    val chip: String,
    /** 传给 qemu 的 board：-M miwear,profile=<qemuBoard>。 */
    val qemuBoard: String,
    /** 固件产品名前缀（miwear.watch.p65 可匹配 p65cn 等）。 */
    val productPrefixes: List<String>,
    val screen: ScreenSpec,
    val buttons: List<HardwareButton>,
    val storage: StorageLayout,
    /** 承载控制台 / NSH 的 UART 序号。 */
    val consoleUart: Int,
    val uartCount: Int,
    val accentColor: Int,
    val notes: String,
    val defaultsDidPrefix: String,
    val defaultsPsnPrefix: String,
) {
    fun runsProduct(product: String): Boolean =
        productPrefixes.any { product.startsWith(it) }

    fun toJson(): JsonValue.Obj = jsonObject(
        "id" to js(id),
        "name" to js(name),
        "family" to js(family.name),
        "chip" to js(chip),
        "qemuBoard" to js(qemuBoard),
        "productPrefixes" to productPrefixes.toJsonArray(),
        "screen" to jsonObject(
            "width" to jn(screen.width),
            "height" to jn(screen.height),
            "shape" to js(screen.shape.name),
            "cornerRadius" to jn(screen.cornerRadius),
        ),
        "buttons" to jsonArray(buttons.map {
            jsonObject(
                "id" to js(it.id),
                "title" to js(it.title),
                "qcode" to js(it.qcode),
                "placement" to js(it.placement.name),
            )
        }),
        "consoleUart" to jn(consoleUart),
        "uartCount" to jn(uartCount),
        "accentColor" to js("#%06X".format(accentColor and 0xFFFFFF)),
        "notes" to js(notes),
    )
}

object DeviceProfiles {
    /** REDMI Watch 6：432x514 圆角面板，best1502p，SPI NAND。首批目标。 */
    val P65 = DeviceProfile(
        id = "p65",
        name = "REDMI Watch 6",
        family = DeviceFamily.WATCH,
        chip = "best1502p",
        qemuBoard = "p65",
        productPrefixes = listOf("miwear.watch.p65"),
        screen = ScreenSpec(432, 514, ScreenShape.ROUNDED_RECT, 48),
        buttons = listOf(
            HardwareButton("home", "上键", "home", ButtonPlacement.RIGHT_UPPER),
            HardwareButton("down", "电源键", "pgdn", ButtonPlacement.RIGHT_LOWER),
        ),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048,
                oobSize = 64,
                pagesPerBlock = 64,
                blocks = 4096,
                resourceImage = "vela_resource.bin",
                resourceFirstPage = 0,
                resourcePages = 112640,
                systemPages = null,
                dataPages = 149504,
                dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true,
                offset = 0xCC0000L,
                blockSize = 256,
                blockCount = 1,
                format = FactoryFormat.P65_RECORD,
            ),
        ),
        consoleUart = 1,
        uartCount = 3,
        accentColor = 0xFF5722,
        notes = "432x514 圆角面板（rm690c0），Zinitix 触摸 I2C5，SPI NAND（resource romfs + /data）。",
        defaultsDidPrefix = "1502",
        defaultsPsnPrefix = "QEMU1502",
    )

    /** 小米手环 11：212x520 长条面板，best1503，SPI NAND。首批目标。 */
    val Q66 = DeviceProfile(
        id = "q66",
        name = "小米手环 11",
        family = DeviceFamily.BAND,
        chip = "best1503",
        qemuBoard = "q66",
        productPrefixes = listOf("miwear.watch.q66"),
        screen = ScreenSpec(212, 520, ScreenShape.ROUNDED_RECT, 104),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048,
                oobSize = 64,
                pagesPerBlock = 64,
                blocks = 4096,
                resourceImage = "vela_resource.bin",
                resourceFirstPage = 0,
                resourcePages = 65536,
                systemPages = null,
                dataPages = 196608,
                dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true,
                offset = 32256L * 512,
                blockSize = 8192,
                blockCount = 16,
                format = FactoryFormat.LITTLEFS,
            ),
        ),
        consoleUart = 2,
        uartCount = 3,
        accentColor = 0x5AC8FA,
        notes = "212x520 长条面板，CST92xx 触摸，SPI NAND（resource romfs + /data）。",
        defaultsDidPrefix = "150",
        defaultsPsnPrefix = "QEMU1503",
    )

    /** 预留：REDMI Watch 7（q65，同 p65 面板尺寸）。 */
    val Q65 = DeviceProfile(
        id = "q65",
        name = "REDMI Watch 7",
        family = DeviceFamily.WATCH,
        chip = "best1502p",
        qemuBoard = "q65",
        productPrefixes = listOf("miwear.watch.q65"),
        screen = ScreenSpec(432, 514, ScreenShape.ROUNDED_RECT, 48),
        buttons = listOf(
            HardwareButton("home", "上键", "home", ButtonPlacement.RIGHT_UPPER),
            HardwareButton("down", "电源键", "pgdn", ButtonPlacement.RIGHT_LOWER),
        ),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048, oobSize = 64, pagesPerBlock = 64, blocks = 4096,
                resourceImage = "vela_resource.bin", resourceFirstPage = 0,
                resourcePages = 120320, systemPages = null,
                dataPages = 118784, dataStartPage = 122880,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 0xCC0000L, blockSize = 256,
                blockCount = 1, format = FactoryFormat.P65_RECORD,
            ),
        ),
        consoleUart = 1,
        uartCount = 3,
        accentColor = 0xE91E63,
        notes = "预留：432x514 面板，CST9317 触摸，SPI NAND。",
        defaultsDidPrefix = "1502",
        defaultsPsnPrefix = "QEMU1502",
    )

    /** 预留：小米手环 10 Pro（p67，336x480）。 */
    val P67 = DeviceProfile(
        id = "p67",
        name = "小米手环 10 Pro",
        family = DeviceFamily.BAND,
        chip = "best1503",
        qemuBoard = "p67",
        productPrefixes = listOf("miwear.watch.p67"),
        screen = ScreenSpec(336, 480, ScreenShape.ROUNDED_RECT, 48),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048, oobSize = 64, pagesPerBlock = 64, blocks = 4096,
                resourceImage = "vela_resource.bin", resourceFirstPage = 0,
                resourcePages = 0x19000, systemPages = null,
                dataPages = 133120, dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 32512L * 512, blockSize = 8192,
                blockCount = 16, format = FactoryFormat.LITTLEFS,
            ),
        ),
        consoleUart = 2,
        uartCount = 3,
        accentColor = 0x7B8CFF,
        notes = "预留：336x480 面板，SPI NAND。",
        defaultsDidPrefix = "1503",
        defaultsPsnPrefix = "QEMU1503",
    )

    /** 预留：小米手环 10（o66，212x520）。 */
    val O66 = DeviceProfile(
        id = "o66",
        name = "小米手环 10",
        family = DeviceFamily.BAND,
        chip = "best1503",
        qemuBoard = "o66",
        productPrefixes = listOf("miwear.watch.o66"),
        screen = ScreenSpec(212, 520, ScreenShape.ROUNDED_RECT, 104),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048, oobSize = 64, pagesPerBlock = 64, blocks = 4096,
                resourceImage = "", resourceFirstPage = 0, resourcePages = 0,
                systemPages = 65536, dataPages = 196608, dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 16368L * 512, blockSize = 8192,
                blockCount = 1, format = FactoryFormat.RAW_INFO,
            ),
        ),
        consoleUart = 2,
        uartCount = 3,
        accentColor = 0xFF9F0A,
        notes = "预留：212x520 面板，yaffs /system + /data。",
        defaultsDidPrefix = "150",
        defaultsPsnPrefix = "QEMU1503",
    )

    /** 预留：小米手环 9（n66，192x490）。 */
    val N66 = DeviceProfile(
        id = "n66",
        name = "小米手环 9",
        family = DeviceFamily.BAND,
        chip = "best1503",
        qemuBoard = "n66",
        productPrefixes = listOf("miwear.watch.n66"),
        screen = ScreenSpec(192, 490, ScreenShape.ROUNDED_RECT, 96),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048, oobSize = 64, pagesPerBlock = 64, blocks = 4096,
                resourceImage = "", resourceFirstPage = 0, resourcePages = 0,
                systemPages = 65536, dataPages = 65536, dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 16368L * 512, blockSize = 8192,
                blockCount = 1, format = FactoryFormat.RAW_INFO,
            ),
        ),
        consoleUart = 2,
        uartCount = 3,
        accentColor = 0x34C759,
        notes = "预留：192x490 面板，yaffs /system + /data。",
        defaultsDidPrefix = "150",
        defaultsPsnPrefix = "QEMU1503",
    )

    /** 预留：小米手环 9 Pro（n67，336x480）。 */
    val N67 = DeviceProfile(
        id = "n67",
        name = "小米手环 9 Pro",
        family = DeviceFamily.BAND,
        chip = "best1503",
        qemuBoard = "n67",
        productPrefixes = listOf("miwear.watch.n67"),
        screen = ScreenSpec(336, 480, ScreenShape.ROUNDED_RECT, 48),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = NandSpec(
                pageSize = 2048, oobSize = 64, pagesPerBlock = 64, blocks = 4096,
                resourceImage = "", resourceFirstPage = 0, resourcePages = 0,
                systemPages = 57600, dataPages = 73472, dataStartPage = null,
            ),
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 16368L * 512, blockSize = 8192,
                blockCount = 1, format = FactoryFormat.RAW_INFO,
            ),
        ),
        consoleUart = 1,
        uartCount = 3,
        accentColor = 0xFF9F0A,
        notes = "预留：336x480 面板，yaffs /system + /data。",
        defaultsDidPrefix = "150",
        defaultsPsnPrefix = "QEMU1506",
    )

    /** 预留：小米手表 S5（q63，466x466 圆形，eMMC）。 */
    val Q63 = DeviceProfile(
        id = "q63",
        name = "小米手表 S5",
        family = DeviceFamily.WATCH,
        chip = "best1700",
        qemuBoard = "q63",
        productPrefixes = listOf("miwear.watch.q63"),
        screen = ScreenSpec(466, 466, ScreenShape.CIRCLE, 233),
        buttons = listOf(
            HardwareButton("home", "表冠", "home", ButtonPlacement.CROWN),
            HardwareButton("down", "侧键", "pgdn", ButtonPlacement.RIGHT_LOWER),
        ),
        storage = StorageLayout(
            mainNor = false,
            sensorNorSize = 0x80_0000L,
            nand = null,
            emmc = EmmcSpec(
                sizeGiB = 4,
                romfs = listOf(
                    "vendor", "system", "app", "health", "misc", "i18n", "font",
                    "watchface", "quickapp", "cp_image", "audio_image", "sensor", "store",
                ),
            ),
            factory = FactoryPartition(
                mainNor = false, offset = 0x7800L * 256, blockSize = 4096,
                blockCount = 64, format = FactoryFormat.LITTLEFS,
            ),
        ),
        consoleUart = 0,
        uartCount = 3,
        accentColor = 0xF2A65A,
        notes = "预留：466x466 圆形 AMOLED，eMMC（GPT），表冠=HOME，侧键=DOWN。",
        defaultsDidPrefix = "1700",
        defaultsPsnPrefix = "QEMU1700",
    )

    /** 全部内置 profile；p65 / q66 排在最前（首批实测目标）。 */
    val builtins: List<DeviceProfile> = listOf(P65, Q66, Q65, P67, O66, N66, N67, Q63)

    fun byId(id: String): DeviceProfile? = builtins.firstOrNull { it.id == id }

    /**
     * 找不到内置 profile 时的兜底：返回一个仅用于显示、不可启动的占位 profile。
     * 设备文件可能是旧版本写的，不能让 UI 崩。
     */
    fun require(id: String): DeviceProfile = byId(id) ?: fallback(id)

    fun fallback(id: String): DeviceProfile = DeviceProfile(
        id = id,
        name = "未知机型（$id）",
        family = DeviceFamily.WATCH,
        chip = "",
        qemuBoard = id,
        productPrefixes = emptyList(),
        screen = ScreenSpec(432, 514, ScreenShape.ROUNDED_RECT, 48),
        buttons = emptyList(),
        storage = StorageLayout(
            mainNor = true,
            sensorNorSize = null,
            nand = null,
            emmc = null,
            factory = FactoryPartition(
                mainNor = true, offset = 0, blockSize = 256,
                blockCount = 1, format = FactoryFormat.LITTLEFS,
            ),
        ),
        consoleUart = 1,
        uartCount = 3,
        accentColor = 0x9E9E9E,
        notes = "本版本没有该机型的 profile，设备不可启动。",
        defaultsDidPrefix = "0000",
        defaultsPsnPrefix = "QEMU0000",
    )
}
