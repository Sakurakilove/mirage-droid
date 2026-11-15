/* devlib_profile — the built-in device profiles.
 *
 * Values are a line-by-line port of Mirage's DeviceProfile built-ins
 * (Packages/MirageKit/Sources/MirageKit/Models/DeviceProfile.swift):
 * screen, buttons, storage layout (main NOR / sensor NOR / SPI NAND /
 * eMMC GPT), console UART and factory defaults all match, so the images
 * this library builds are the ones Mirage provisions.
 */
#include <string.h>

#include "devlib_internal.h"

static const dl_profile g_profiles[] = {
    /* Xiaomi Smart Band 11: board q66 (best1503), 212x520. Resource romfs
     * at page 0 (65536 pages), /data at page 65536 (196608 pages). */
    {
        .id = "q66",
        .name = "Xiaomi Smart Band 11",
        .family = DL_FAMILY_BAND,
        .chip = "best1503",
        .qemu_board = "q66",
        .product_prefixes = { "miwear.watch.q66" },
        .product_prefix_count = 1,
        .screen = { 212, 520, DL_SHAPE_ROUNDED_RECT, 104 },
        .buttons = { { NULL, NULL, NULL, 0 } },
        .button_count = 0,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "vela_resource.bin",
            .resource_first_page = 0, .resource_pages = 65536,
            .has_system_pages = 0, .system_pages = 0,
            .has_data_pages = 1, .data_pages = 196608,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_LITTLEFS, DL_NOR_MAIN, 32256ull * 512, 8192, 16, 512, 32,
                     0x00020001u },
        .console_uart = 2,
        .uart_count = 3,
        .did_prefix = "150",
        .psn_prefix = "QEMU1503",
        .sku = NULL,
        .accent = "#5AC8FA",
        .notes = "Panel 212x520, CST92xx touch, SPI NAND (resource romfs + /data). No physical buttons.",
    },

    /* REDMI Watch 6: board p65 (best1502p), 432x514. Resource romfs at page 0
     * (112640 pages), /data at page 112640 (149504 pages), 256-byte p65
     * factory record in the boot NOR at 0xCC0000. */
    {
        .id = "p65",
        .name = "REDMI Watch 6",
        .family = DL_FAMILY_WATCH,
        .chip = "best1502p",
        .qemu_board = "p65",
        .product_prefixes = { "miwear.watch.p65" },
        .product_prefix_count = 1,
        .screen = { 432, 514, DL_SHAPE_ROUNDED_RECT, 48 },
        .buttons = {
            { "home", "Upper Button", "home", DL_PLACE_RIGHT_UPPER },
            { "down", "Power Key", "pgdn", DL_PLACE_RIGHT_LOWER },
        },
        .button_count = 2,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "vela_resource.bin",
            .resource_first_page = 0, .resource_pages = 112640,
            .has_system_pages = 0, .system_pages = 0,
            .has_data_pages = 1, .data_pages = 149504,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_P65RECORD, DL_NOR_MAIN, 0xCC0000ull, 256, 1, 256, 32, 0 },
        .console_uart = 1,
        .uart_count = 3,
        .did_prefix = "1502",
        .psn_prefix = "QEMU1502",
        .sku = "M2509W1",
        .accent = "#FF5722",
        .notes = "Panel 432x514 (rm690c0 driver), Zinitix touch on I2C5, SPI NAND (resource romfs + /data).",
    },

    /* REDMI Watch 7: board q65 (best1502p), 432x514. The /data partition
     * starts at page 122880 while the resource ends at 120320. */
    {
        .id = "q65",
        .name = "REDMI Watch 7",
        .family = DL_FAMILY_WATCH,
        .chip = "best1502p",
        .qemu_board = "q65",
        .product_prefixes = { "miwear.watch.q65" },
        .product_prefix_count = 1,
        .screen = { 432, 514, DL_SHAPE_ROUNDED_RECT, 48 },
        .buttons = {
            { "home", "Upper Button", "home", DL_PLACE_RIGHT_UPPER },
            { "down", "Power Key", "pgdn", DL_PLACE_RIGHT_LOWER },
        },
        .button_count = 2,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "vela_resource.bin",
            .resource_first_page = 0, .resource_pages = 120320,
            .has_system_pages = 0, .system_pages = 0,
            .has_data_pages = 1, .data_pages = 118784,
            .has_data_start_page = 1, .data_start_page = 122880,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_P65RECORD, DL_NOR_MAIN, 0xCC0000ull, 256, 1, 256, 32, 0 },
        .console_uart = 1,
        .uart_count = 3,
        .did_prefix = "1502",
        .psn_prefix = "QEMU1502",
        .sku = "M2611W1",
        .accent = "#E91E63",
        .notes = "Panel 432x514 (rm690c0 driver), CST9317 touch on I2C5, SPI NAND (resource romfs + /data).",
    },

    /* Xiaomi Smart Band 10 Pro: board p67 (best1503), 336x480. */
    {
        .id = "p67",
        .name = "Xiaomi Smart Band 10 Pro",
        .family = DL_FAMILY_BAND,
        .chip = "best1503",
        .qemu_board = "p67",
        .product_prefixes = { "miwear.watch.p67" },
        .product_prefix_count = 1,
        .screen = { 336, 480, DL_SHAPE_ROUNDED_RECT, 48 },
        .buttons = { { NULL, NULL, NULL, 0 } },
        .button_count = 0,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "vela_resource.bin",
            .resource_first_page = 0, .resource_pages = 0x19000,
            .has_system_pages = 0, .system_pages = 0,
            .has_data_pages = 1, .data_pages = 133120,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_LITTLEFS, DL_NOR_MAIN, 32512ull * 512, 8192, 16, 512, 32,
                     0x00020001u },
        .console_uart = 2,
        .uart_count = 3,
        .did_prefix = "1503",
        .psn_prefix = "QEMU1503",
        .sku = NULL,
        .accent = "#7B8CFF",
        .notes = "Panel 336x480, Zinitix touch, SPI NAND (resource romfs + /data). No physical buttons.",
    },

    /* Xiaomi Smart Band 9: board n66 (best1503); /system is a yaffs2
     * partition built from the package's main_res files. */
    {
        .id = "n66",
        .name = "Xiaomi Smart Band 9",
        .family = DL_FAMILY_BAND,
        .chip = "best1503",
        .qemu_board = "n66",
        .product_prefixes = { "miwear.watch.n66" },
        .product_prefix_count = 1,
        .screen = { 192, 490, DL_SHAPE_ROUNDED_RECT, 96 },
        .buttons = { { NULL, NULL, NULL, 0 } },
        .button_count = 0,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "",
            .resource_first_page = 0, .resource_pages = 0,
            .has_system_pages = 1, .system_pages = 65536,
            .has_data_pages = 1, .data_pages = 65536,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_RAWINFO, DL_NOR_MAIN, 16368ull * 512, 8192, 1, 512, 32, 0 },
        .console_uart = 2,
        .uart_count = 3,
        .did_prefix = "150",
        .psn_prefix = "QEMU1503",
        .sku = NULL,
        .accent = "#34C759",
        .notes = "Panel 192x490, Hynitron CST92xx touch, SPI NAND (yaffs /system + /data). No physical buttons.",
    },

    /* Xiaomi Smart Band 10: board o66 (best1503). */
    {
        .id = "o66",
        .name = "Xiaomi Smart Band 10",
        .family = DL_FAMILY_BAND,
        .chip = "best1503",
        .qemu_board = "o66",
        .product_prefixes = { "miwear.watch.o66" },
        .product_prefix_count = 1,
        .screen = { 212, 520, DL_SHAPE_ROUNDED_RECT, 104 },
        .buttons = { { NULL, NULL, NULL, 0 } },
        .button_count = 0,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "",
            .resource_first_page = 0, .resource_pages = 0,
            .has_system_pages = 1, .system_pages = 65536,
            .has_data_pages = 1, .data_pages = 196608,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_RAWINFO, DL_NOR_MAIN, 16368ull * 512, 8192, 1, 512, 32, 0 },
        .console_uart = 2,
        .uart_count = 3,
        .did_prefix = "150",
        .psn_prefix = "QEMU1503",
        .sku = NULL,
        .accent = "#FF9F0A",
        .notes = "Panel 212x520, CST9217M touch, SPI NAND (yaffs /system + /data). No physical buttons.",
    },

    /* Xiaomi Smart Band 9 Pro: board n67 (best1503). */
    {
        .id = "n67",
        .name = "Xiaomi Smart Band 9 Pro",
        .family = DL_FAMILY_BAND,
        .chip = "best1503",
        .qemu_board = "n67",
        .product_prefixes = { "miwear.watch.n67" },
        .product_prefix_count = 1,
        .screen = { 336, 480, DL_SHAPE_ROUNDED_RECT, 48 },
        .buttons = { { NULL, NULL, NULL, 0 } },
        .button_count = 0,
        .main_nor = 1,
        .has_sensor_nor = 0,
        .has_nand = 1,
        .nand = {
            .page_size = 2048, .oob_size = 64, .pages_per_block = 64, .blocks = 4096,
            .resource_image = "",
            .resource_first_page = 0, .resource_pages = 0,
            .has_system_pages = 1, .system_pages = 57600,
            .has_data_pages = 1, .data_pages = 73472,
            .has_data_start_page = 0, .data_start_page = 0,
        },
        .has_emmc = 0,
        .factory = { DL_FACTORY_RAWINFO, DL_NOR_MAIN, 16368ull * 512, 8192, 1, 512, 32, 0 },
        .console_uart = 1,
        .uart_count = 3,
        .did_prefix = "150",
        .psn_prefix = "QEMU1506",
        .sku = NULL,
        .accent = "#FF9F0A",
        .notes = "Panel 336x480 (rm690c0 driver), Hynitron CST92xx touch on I2C5, W1150 light sensor on I2C5, SPI NAND (yaffs /system + /data). No physical buttons.",
    },

    /* Xiaomi Watch S5: board q63 (best1700 + TEE), eMMC GPT, circle panel. */
    {
        .id = "q63",
        .name = "Xiaomi Watch S5",
        .family = DL_FAMILY_WATCH,
        .chip = "best1700",
        .qemu_board = "q63",
        .product_prefixes = { "miwear.watch.q63" },
        .product_prefix_count = 1,
        .screen = { 466, 466, DL_SHAPE_CIRCLE, 0 },
        .buttons = {
            { "home", "Crown", "home", DL_PLACE_CROWN },
            { "down", "Side Button", "pgdn", DL_PLACE_RIGHT_LOWER },
        },
        .button_count = 2,
        .main_nor = 0,
        .has_sensor_nor = 1,
        .sensor_nor_size = 0x800000,
        .has_nand = 0,
        .has_emmc = 1,
        .emmc = {
            .size_gib = 4,
            .romfs = { "vendor", "system", "app", "health", "misc", "i18n", "font",
                       "watchface", "quickapp", "cp_image", "audio_image", "sensor", "store" },
            .romfs_count = 13,
            .blank = {
                { "nv", 1, 32 }, { "data", 1, 1024 }, { "userlog", 1, 64 },
            },
            .blank_count = 3,
        },
        .factory = { DL_FACTORY_LITTLEFS, DL_NOR_SENSOR, 0x7800ull * 256, 4096, 64, 256, 32,
                     0x00020000u },
        .console_uart = 0,
        .uart_count = 3,
        .did_prefix = "1700",
        .psn_prefix = "QEMU1700",
        .sku = NULL,
        .accent = "#F2A65A",
        .notes = "466x466 round AMOLED, Raydium touch, eMMC (GPT), crown = HOME, side button = DOWN.",
    },
};

#define DL_PROFILE_COUNT ((int)(sizeof(g_profiles) / sizeof(g_profiles[0])))

int dl_profile_count(void)
{
    return DL_PROFILE_COUNT;
}

const dl_profile *dl_profile_at(int index)
{
    if (index < 0 || index >= DL_PROFILE_COUNT) {
        return NULL;
    }
    return &g_profiles[index];
}

const dl_profile *dl_profile_by_id(const char *id)
{
    if (!id) {
        return NULL;
    }
    for (int i = 0; i < DL_PROFILE_COUNT; i++) {
        if (strcmp(g_profiles[i].id, id) == 0) {
            return &g_profiles[i];
        }
    }
    return NULL;
}

const dl_profile *dl_profile_for_product(const char *product, const char *chip)
{
    if (!product || !*product) {
        return NULL;
    }
    for (int i = 0; i < DL_PROFILE_COUNT; i++) {
        const dl_profile *p = &g_profiles[i];
        if (chip && *chip && strcmp(p->chip, chip) != 0) {
            continue;
        }
        for (int k = 0; k < p->product_prefix_count; k++) {
            size_t n = strlen(p->product_prefixes[k]);
            if (strncmp(product, p->product_prefixes[k], n) == 0) {
                return p;
            }
        }
    }
    return NULL;
}

uint32_t dl_nand_data_first_page(const dl_nand_spec *spec)
{
    if (spec->has_data_start_page) {
        return spec->data_start_page;
    }
    if (spec->has_system_pages) {
        return spec->system_pages;
    }
    return spec->resource_first_page + spec->resource_pages;
}

uint32_t dl_nand_data_page_count(const dl_nand_spec *spec)
{
    if (spec->has_data_pages) {
        return spec->data_pages;
    }
    return spec->blocks * spec->pages_per_block - dl_nand_data_first_page(spec);
}

uint64_t dl_nand_capacity(const dl_nand_spec *spec)
{
    return (uint64_t)spec->page_size * spec->pages_per_block * spec->blocks;
}

uint64_t dl_nand_image_size(const dl_nand_spec *spec)
{
    return (uint64_t)(spec->page_size + spec->oob_size) * spec->pages_per_block * spec->blocks;
}

uint64_t dl_factory_partition_size(const dl_factory_part *part)
{
    return (uint64_t)part->block_size * part->block_count;
}
