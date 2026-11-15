/*
 * devlib — MirageDroid device core: device profiles, OTA import and
 * storage-image builders (NOR / SPI NAND / eMMC GPT), plus the fixed
 * factory records the firmware reads.
 *
 * A faithful C port of Mirage's
 *   Packages/MirageKit/Sources/MirageKit/{Models/DeviceProfile.swift,
 *     Storage/{ImageBuilder,DeviceStorage,BindSeed}.swift,
 *     Library/FirmwareImporter.swift, Files/{RomFS,Yaffs2}.swift}
 * (https://github.com/AstralSightStudios/Mirage, AGPL-3.0). The generated
 * images are byte-compatible with Mirage's own provisioning.
 *
 * No Android dependencies: the library only needs libc, libm and (for OTA
 * .zip import) the vendored miniz.
 */
#ifndef DEVLIB_H
#define DEVLIB_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* Errors                                                              */
/* ------------------------------------------------------------------ */

enum {
    DL_OK = 0,
    DL_ERR_INVALID = -1,     /* bad argument / unsupported request       */
    DL_ERR_IO = -2,          /* read/write/open failure                  */
    DL_ERR_NOT_FOUND = -3,   /* missing file                             */
    DL_ERR_NOSPACE = -4,     /* image/partition too small                */
    DL_ERR_FORMAT = -5,      /* not the expected format                  */
    DL_ERR_ZLIB = -6,        /* zip entry decompression failed           */
    DL_ERR_LFS = -7          /* littlefs failed                          */
};

/* Human readable text for a devlib error code. */
const char *dl_strerror(int rc);

/* ------------------------------------------------------------------ */
/* Device profiles (mirrors Mirage DeviceProfile)                      */
/* ------------------------------------------------------------------ */

typedef enum { DL_FAMILY_BAND = 0, DL_FAMILY_WATCH = 1 } dl_family;
typedef enum { DL_SHAPE_ROUNDED_RECT = 0, DL_SHAPE_CIRCLE = 1 } dl_shape;
typedef enum {
    DL_PLACE_CROWN = 0, DL_PLACE_RIGHT_UPPER = 1,
    DL_PLACE_RIGHT_LOWER = 2, DL_PLACE_LEFT = 3
} dl_placement;
typedef enum { DL_NOR_MAIN = 0, DL_NOR_SENSOR = 1 } dl_nor_flash;

/* How the factory data is stored in its partition. */
typedef enum {
    DL_FACTORY_LITTLEFS = 0,  /* littlefs with /factory.prop           */
    DL_FACTORY_RAWINFO = 1,   /* 1292 B raw record (Band 9 / 10)       */
    DL_FACTORY_P65RECORD = 2  /* 256 B fixed record (REDMI Watch 6)    */
} dl_factory_format;

typedef struct {
    uint32_t width;
    uint32_t height;
    dl_shape shape;
    uint32_t corner_radius;
} dl_screen;

typedef struct {
    const char *id;
    const char *title;
    const char *qcode;         /* QEMU key code the board listens for */
    dl_placement placement;
} dl_button;

typedef struct {
    uint32_t page_size;
    uint32_t oob_size;
    uint32_t pages_per_block;
    uint32_t blocks;
    const char *resource_image;  /* e.g. "vela_resource.bin"; "" if none */
    uint32_t resource_first_page;
    uint32_t resource_pages;
    int has_system_pages;        /* yaffs2 /system at page 0 */
    uint32_t system_pages;
    int has_data_pages;
    uint32_t data_pages;
    int has_data_start_page;
    uint32_t data_start_page;
} dl_nand_spec;

typedef struct {
    const char *name;            /* GPT partition name */
    int blank;                   /* 1 = firmware formats it itself */
    uint32_t mib;                /* blank partition size */
} dl_emmc_part;

typedef struct {
    int size_gib;
    const char *romfs[16];       /* vela_<name>.bin partitions */
    int romfs_count;
    dl_emmc_part blank[8];
    int blank_count;
} dl_emmc_spec;

typedef struct {
    dl_factory_format format;
    dl_nor_flash flash;
    uint64_t offset;
    uint32_t block_size;
    uint32_t block_count;
    uint32_t io_size;
    uint32_t name_max;
    uint32_t disk_version;
} dl_factory_part;

typedef struct {
    const char *id;
    const char *name;
    dl_family family;
    const char *chip;            /* boot info CHIP= */
    const char *qemu_board;      /* -M miwear,profile=<board> */
    const char *product_prefixes[4];
    int product_prefix_count;
    dl_screen screen;
    dl_button buttons[4];
    int button_count;
    /* StorageLayout */
    int main_nor;
    int has_sensor_nor;
    uint32_t sensor_nor_size;
    int has_nand;
    dl_nand_spec nand;
    int has_emmc;
    dl_emmc_spec emmc;
    dl_factory_part factory;
    /* Runtime */
    int console_uart;
    int uart_count;
    /* FactoryDefaults */
    const char *did_prefix;
    const char *psn_prefix;
    const char *sku;             /* may be NULL */
    const char *accent;
    const char *notes;
} dl_profile;

int dl_profile_count(void);
const dl_profile *dl_profile_at(int index);
const dl_profile *dl_profile_by_id(const char *id);
/* The profile whose product prefixes match `product` and whose chip is
 * `chip` (either may be NULL / empty). NULL when nothing matches. */
const dl_profile *dl_profile_for_product(const char *product, const char *chip);

/* Derived helpers, all mirroring the Swift computed properties. */
uint64_t dl_nand_capacity(const dl_nand_spec *spec);   /* data bytes      */
uint64_t dl_nand_image_size(const dl_nand_spec *spec); /* data + OOB      */
uint32_t dl_nand_data_first_page(const dl_nand_spec *spec);
uint32_t dl_nand_data_page_count(const dl_nand_spec *spec);
uint64_t dl_factory_partition_size(const dl_factory_part *part);

/* ------------------------------------------------------------------ */
/* Factory identity (what the factory line writes)                     */
/* ------------------------------------------------------------------ */

typedef struct {
    char did[32];      /* ro.factory.did, decimal digits          */
    char psn[32];      /* ro.factory.psn, 1..31 printable chars   */
    char mac_bt[18];   /* ro.factory.mac_bt, "XX:XX:XX:XX:XX:XX"  */
    char sku[32];      /* ro.factory.sku (may be empty)           */
    char key[40];      /* ro.factory.key (p65 record only)        */
    char fsn[40];      /* ro.factory.fsn (p65 record only)        */
} dl_identity;

/* Generate an identity the way Mirage FactoryIdentity.generate does:
 * did = didPrefix + "%06u", psn = psnPrefix + digits up to 18 chars,
 * locally-administered unicast MAC, sku from the profile. */
void dl_identity_generate(const dl_profile *profile, uint32_t seed, dl_identity *out);
int dl_identity_validate(const dl_identity *identity);
int dl_mac_valid(const char *mac);

/* `kind` = "did" | "psn" | "mac_bt" | "key" | "fsn" | "sku".
 * Returns 0 on success, DL_ERR_NOT_FOUND when the identity has no field. */
int dl_identity_get(const dl_identity *identity, const char *kind, char *out, size_t cap);
int dl_identity_set(dl_identity *identity, const char *kind, const char *value);

/* The band's view of the phone it is bound to (Mirage BindInfo). The
 * persist.* keys go into /factory.prop (littlefs factory) and, for the raw
 * record devices, into the seeded /data databases (dl_nand_seed_bind). */
typedef struct {
    int bound;
    char auth_key[33];    /* persist.miwear_token_key, 32 hex */
    char user_id[16];     /* persist.miwear_user_id             */
    char beacon_key[33];
    char ir_key[33];
    char seqnum[8];
} dl_bind;

void dl_bind_generate(uint32_t seed, dl_bind *out);
int dl_bind_validate(const dl_bind *bind);

/* Saves / loads a dl_identity as KEY=VALUE lines (did, psn, mac_bt, sku,
 * key, fsn), plus the bind keys when `bind` is not NULL. */
int dl_identity_save(const dl_identity *identity, const dl_bind *bind, const char *path);
int dl_identity_load(dl_identity *identity, dl_bind *bind, const char *path);

/* ------------------------------------------------------------------ */
/* OTA (firmware) package import                                       */
/* ------------------------------------------------------------------ */

typedef struct {
    char name[256];
    uint64_t size;        /* uncompressed size */
} dl_zip_entry;

typedef struct {
    dl_zip_entry *entries;
    size_t count;
} dl_zip_list;

/* Lists the entries of a .zip package (any standard zip; stored and
 * deflate entries both supported). */
int dl_zip_scan(const char *zip_path, dl_zip_list *out);
void dl_zip_list_free(dl_zip_list *list);
int dl_zip_extract(const char *zip_path, const char *entry_name, const char *out_path,
                   uint64_t *out_size);
int dl_zip_extract_to_mem(const char *zip_path, const char *entry_name,
                          void **out, size_t *out_size);
int dl_zip_entry_size(const char *zip_path, const char *entry_name, uint64_t *out_size);

/* Facts read out of vela_ap.bin's boot info (CHIP=, FLASH_SIZE=, ...). */
typedef struct {
    char chip[32];
    int has_flash_size;
    uint64_t flash_size;
    int has_ota_code_offset;
    uint64_t ota_code_offset;
    char build_date[64];
    char version[64];
    char customer_version[64];
    char products[16][40];
    int product_count;
} dl_ap_info;

int dl_ap_read_info(const char *ap_path, dl_ap_info *out);
int dl_ap_parse_info(const void *data, size_t size, dl_ap_info *out);

/* One article of the import report. */
typedef struct {
    char name[256];
    uint64_t size;
} dl_import_file;

typedef struct {
    char product[64];
    char version[64];
    char chip[32];
    uint64_t flash_size;
    dl_import_file files[32];
    int file_count;
    uint64_t total_bytes;
} dl_import_report;

/* Imports a firmware package: `source` is either an official OTA .zip or a
 * directory that holds vela_ap.bin. Keeps the vela_*.bin images, ota.sh,
 * ota.json (and the main_res files it names), and copies them into
 * `out_dir` (created if needed). */
int dl_ota_import(const char *source, const char *out_dir, dl_import_report *report);

/* ------------------------------------------------------------------ */
/* ROMFS (read-only NuttX romfs, the resource partition)               */
/* ------------------------------------------------------------------ */

typedef struct {
    char name[256];
    int is_dir;
    uint64_t size;
    uint32_t data_offset;   /* into the image */
} dl_romfs_entry;

typedef struct {
    int is_dir;
    uint64_t size;
    uint32_t data_offset;
} dl_romfs_stat;

int dl_romfs_check(const char *image_path);   /* DL_OK when "-rom1fs-" */
int dl_romfs_lookup(const char *image_path, const char *path, dl_romfs_stat *out);
int dl_romfs_list(const char *image_path, const char *path,
                  dl_romfs_entry **out, size_t *count);
void dl_romfs_entries_free(dl_romfs_entry *entries);
int dl_romfs_read(const char *image_path, const char *path, void **out, size_t *size);

/* ------------------------------------------------------------------ */
/* Image builders                                                      */
/* ------------------------------------------------------------------ */

/* Erases (0xFF) a NOR image of `size` bytes at `path`. */
int dl_nor_create(const char *path, uint64_t size);

/* The bytes of a factory partition (littlefs, rawInfo or p65Record). */
int dl_factory_build(const dl_factory_part *part, const dl_identity *identity,
                     const dl_bind *bind, void **out, size_t *out_size);
int dl_factory_write(const char *image_path, const dl_factory_part *part,
                     const dl_identity *identity, const dl_bind *bind);
int dl_factory_read(const char *image_path, const dl_factory_part *part,
                    dl_identity *out);
/* Reads /factory.prop from a littlefs factory partition into `out`
 * (newline separated KEY=VALUE, NUL terminated). */
int dl_factory_read_text(const char *image_path, const dl_factory_part *part,
                         char *out, size_t cap);

/* Writes AP firmware into a NOR image at `offset` (the boot NOR carries the
 * BL2/AP region; qemu installs -kernel itself, but a real-NOR image wants
 * the same bytes). */
int dl_nor_install_ap(const char *nor_path, const char *ap_path, uint64_t offset);

/* Page-major SPI NAND image: page data then OOB per page, erased = 0xFF,
 * with the resource romfs written at `resource_first_page`.
 * `system_dir` may be NULL for a resource-romfs layout; for a yaffs2
 * /system layout it is the firmware directory (ota.json / system/). */
int dl_nand_build(const char *path, const dl_nand_spec *spec,
                  const char *resource_path, const char *system_dir);

/* Rewrites only the resource partition of an existing NAND image. */
int dl_nand_update_resource(const char *path, const dl_nand_spec *spec,
                            const char *resource_path);

/* GPT eMMC image (sparse when the filesystem supports it): 1 MiB aligned
 * romfs partitions filled from vela_<name>.bin, then blank partitions. */
int dl_emmc_build(const char *path, const dl_emmc_spec *spec, const char *firmware_dir);
int dl_emmc_list_partitions(const char *image_path, char *out, size_t cap);

/* Cheap structural verification: image sizes / magics / record fields. */
typedef struct {
    int ok;
    char detail[1024];
} dl_verify_result;

int dl_verify_device(const dl_profile *profile, const char *nor_path,
                     const char *nand_path, const dl_identity *identity,
                     dl_verify_result *out);

/* ------------------------------------------------------------------ */
/* One-shot provisioning (what the Android UI and mkdevice call)       */
/* ------------------------------------------------------------------ */

typedef struct {
    const char *firmware_dir;    /* holds vela_ap.bin / vela_resource.bin */
    const char *device_dir;      /* receives nor.img / nand.img / ...     */
    const char *bind_config_dir; /* BindTemplate dir; NULL = no seed     */
    int seed_bind;               /* write bind databases into /data      */
    const char *resource_override; /* NULL: firmware_dir/vela_resource.bin */
} dl_provision_options;

/* Creates every image the profile needs in device_dir (nor.img,
 * sensor-nor.img, nand.img, emmc.img), writing the factory data. The
 * identity used is written to device_dir/identity.properties. */
int dl_provision(const dl_profile *profile, const dl_identity *identity,
                 const dl_bind *bind, const dl_provision_options *options,
                 dl_verify_result *verify);

/* ------------------------------------------------------------------ */
/* yaffs2 bind seed (optional; needs templates from Mirage)            */
/* ------------------------------------------------------------------ */

/* Writes persist.db / misc/bt/miwear_record.db / bt database into the
 * /data partition of an erased NAND image, so the firmware starts bound.
 * `config_dir` is the directory holding the four template .db files.
 * This matches Mirage BindSeed.apply; returns DL_ERR_NOT_FOUND when the
 * templates are absent. */
int dl_nand_seed_bind(const char *nand_path, const dl_nand_spec *spec,
                      const dl_bind *bind);

/* ------------------------------------------------------------------ */
/* CRC / helpers                                                       */
/* ------------------------------------------------------------------ */

uint32_t dl_crc32(const void *data, size_t size);
uint32_t dl_crc32_zero_init(const void *data, size_t size);
void dl_srand(uint32_t seed);
uint32_t dl_rand(void);

/* Last error message (thread-local-ish; one slot per process). */
const char *dl_last_error(void);
void dl_set_error(const char *fmt, ...);

#ifdef __cplusplus
}
#endif

#endif /* DEVLIB_H */
