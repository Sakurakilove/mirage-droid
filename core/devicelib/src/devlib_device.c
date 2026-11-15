/* devlib_device — one-shot provisioning and verification.
 *
 * Mirrors Mirage DeviceStorage.provision: erased NORs, the factory
 * partition written in place, the NAND image with the firmware's resource
 * romfs (or yaffs2 /system), the optional eMMC image and the bind
 * databases in the erased /data partition.
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"
#include "devlib_yaffs2.h"

/* ------------------------------------------------------------------ */
/* NOR                                                                 */
/* ------------------------------------------------------------------ */

int dl_nor_create(const char *path, uint64_t size)
{
    FILE *f;
    uint8_t buf[1 << 16];
    uint64_t left = size;
    size_t chunk = sizeof(buf);

    if (!path) {
        return DL_ERR_INVALID;
    }
    f = fopen(path, "wb");
    if (!f) {
        dl_set_error("cannot create %s", path);
        return DL_ERR_IO;
    }
    memset(buf, 0xFF, sizeof(buf));
    while (left > 0) {
        size_t n = left < chunk ? (size_t)left : chunk;
        if (fwrite(buf, 1, n, f) != n) {
            fclose(f);
            dl_set_error("cannot write %s", path);
            return DL_ERR_IO;
        }
        left -= n;
    }
    if (fclose(f) != 0) {
        return DL_ERR_IO;
    }
    return DL_OK;
}

int dl_nor_install_ap(const char *nor_path, const char *ap_path, uint64_t offset)
{
    FILE *nor, *ap;
    uint8_t buf[1 << 16];
    size_t n;
    int64_t ap_size = dl__file_size(ap_path);
    int64_t nor_size = dl__file_size(nor_path);

    if (ap_size < 0 || nor_size < 0) {
        dl_set_error("cannot size the NOR or AP image");
        return DL_ERR_IO;
    }
    if (offset + (uint64_t)ap_size > (uint64_t)nor_size) {
        dl_set_error("the AP image does not fit the NOR at 0x%llx",
                     (unsigned long long)offset);
        return DL_ERR_NOSPACE;
    }
    nor = fopen(nor_path, "r+b");
    if (!nor) {
        return DL_ERR_IO;
    }
    ap = fopen(ap_path, "rb");
    if (!ap) {
        fclose(nor);
        return DL_ERR_IO;
    }
    if (fseek(nor, (long)offset, SEEK_SET) != 0) {
        fclose(nor);
        fclose(ap);
        return DL_ERR_IO;
    }
    while ((n = fread(buf, 1, sizeof(buf), ap)) > 0) {
        if (fwrite(buf, 1, n, nor) != n) {
            fclose(nor);
            fclose(ap);
            dl_set_error("cannot install the AP image into %s", nor_path);
            return DL_ERR_IO;
        }
    }
    fclose(nor);
    fclose(ap);
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Bind detection: which Bluetooth database the firmware opens         */
/* ------------------------------------------------------------------ */

/* Portable memmem: find `needle` in `haystack`. */
static const uint8_t *find_bytes(const uint8_t *haystack, size_t haystack_len,
                                const char *needle)
{
    size_t nlen = strlen(needle);
    if (nlen == 0 || haystack_len < nlen) {
        return NULL;
    }
    for (size_t i = 0; i + nlen <= haystack_len; i++) {
        if (!memcmp(haystack + i, needle, nlen)) {
            return haystack + i;
        }
    }
    return NULL;
}

static const char *bt_database_for(const char *ap_path, char *scratch, size_t cap)
{
    FILE *f = fopen(ap_path, "rb");
    if (f) {
        char buf[1 << 16];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            if (find_bytes((const uint8_t *)buf, n, "/data/misc/bt/bt_config.db")) {
                fclose(f);
                return "bt_config.db";
            }
        }
        fclose(f);
    }
    (void)scratch;
    (void)cap;
    return "bt_storage.db";
}

/* Case-insensitive equality of two "AA:BB:.." strings without strcasecmp. */
static int mac_equal(const char *a, const char *b)
{
    while (*a && *b) {
        unsigned char ca = (unsigned char)*a++;
        unsigned char cb = (unsigned char)*b++;
        if (ca >= 'a' && ca <= 'z') {
            ca = (unsigned char)(ca - 'a' + 'A');
        }
        if (cb >= 'a' && cb <= 'z') {
            cb = (unsigned char)(cb - 'a' + 'A');
        }
        if (ca != cb) {
            return 0;
        }
    }
    return *a == 0 && *b == 0;
}

/* Writes the bind databases into the erased /data partition. */
static int seed_bind(const char *nand_path, const dl_nand_spec *spec,
                     const dl_bind *bind, const char *config_dir,
                     const char *ap_path)
{
    uint8_t(*pages)[2048] = NULL;
    size_t count = 0;
    char scratch[64];
    const char *db = bt_database_for(ap_path, scratch, sizeof(scratch));
    uint32_t data_first = dl_nand_data_first_page(spec);
    uint32_t capacity = dl_nand_data_page_count(spec);
    uint32_t stride = spec->page_size + spec->oob_size;
    FILE *f;
    uint8_t *page;
    int rc;

    if (!bind || !bind->bound) {
        return DL_OK;
    }
    rc = dl_seed_bind_build(config_dir, db, bind, NULL, &pages, &count);
    if (rc != DL_OK) {
        return rc;
    }
    if (count > capacity) {
        dl_seed_bind_free(pages);
        dl_set_error("the bind databases do not fit the /data partition");
        return DL_ERR_NOSPACE;
    }
    f = fopen(nand_path, "r+b");
    if (!f) {
        dl_seed_bind_free(pages);
        return DL_ERR_IO;
    }
    page = malloc(stride);
    if (!page) {
        fclose(f);
        dl_seed_bind_free(pages);
        return DL_ERR_IO;
    }
    rc = DL_OK;
    for (size_t i = 0; i < count && rc == DL_OK; i++) {
        memset(page, 0xFF, stride);
        memcpy(page, pages[i], spec->page_size);
        if (fseek(f, (long)((uint64_t)(data_first + i) * stride), SEEK_SET) != 0 ||
            fwrite(page, 1, stride, f) != stride) {
            dl_set_error("cannot write the bind databases into %s", nand_path);
            rc = DL_ERR_IO;
        }
    }
    free(page);
    fclose(f);
    dl_seed_bind_free(pages);
    return rc;
}

int dl_nand_seed_bind(const char *nand_path, const dl_nand_spec *spec,
                      const dl_bind *bind)
{
    return seed_bind(nand_path, spec, bind, NULL, NULL);
}

/* ------------------------------------------------------------------ */
/* Provisioning                                                        */
/* ------------------------------------------------------------------ */

static int write_factory_target(const char *nor_path, const char *sensor_path,
                                const dl_factory_part *part,
                                const dl_identity *identity, const dl_bind *bind)
{
    const char *target = part->flash == DL_NOR_SENSOR ? sensor_path : nor_path;
    if (!target) {
        dl_set_error("the factory partition's flash image is not part of this profile");
        return DL_ERR_INVALID;
    }
    if (part->format == DL_FACTORY_LITTLEFS) {
        if (dl_bind_validate(bind) != DL_OK) {
            return DL_ERR_INVALID;
        }
    }
    return dl_factory_write(target, part, identity, bind);
}

int dl_provision(const dl_profile *profile, const dl_identity *identity,
                 const dl_bind *bind, const dl_provision_options *options,
                 dl_verify_result *verify)
{
    char nor_path[4096] = "", sensor_path[4096] = "";
    char nand_path[4096] = "", emmc_path[4096] = "";
    char ap_path[4096], resource_path[4096], identity_path[4096];
    char bind_dir[4096] = "";
    dl_ap_info info;
    uint64_t flash_size = 0x1000000;
    int rc;
    int have_nor = 0, have_nand = 0, have_emmc = 0;

    if (!profile || !identity || !options || !options->firmware_dir ||
        !options->device_dir) {
        return DL_ERR_INVALID;
    }
    rc = dl_identity_validate(identity);
    if (rc != DL_OK) {
        return rc;
    }
    if (bind && bind->bound && dl_bind_validate(bind) != DL_OK) {
        return DL_ERR_INVALID;
    }
    if (dl__mkdirs(options->device_dir) != DL_OK) {
        return DL_ERR_IO;
    }
    if (dl__path_join(ap_path, sizeof(ap_path), options->firmware_dir,
                      "vela_ap.bin") != DL_OK) {
        return DL_ERR_INVALID;
    }
    if (dl_ap_read_info(ap_path, &info) == DL_OK && info.has_flash_size) {
        flash_size = info.flash_size;
    }

    /* Boot NOR: erased, AP flash is installed by qemu from -kernel. */
    if (profile->main_nor) {
        if (dl__path_join(nor_path, sizeof(nor_path), options->device_dir,
                          "nor.img") != DL_OK) {
            return DL_ERR_INVALID;
        }
        rc = dl_nor_create(nor_path, flash_size);
        if (rc != DL_OK) {
            return rc;
        }
        have_nor = 1;
    }
    if (profile->has_sensor_nor) {
        if (dl__path_join(sensor_path, sizeof(sensor_path), options->device_dir,
                          "sensor-nor.img") != DL_OK) {
            return DL_ERR_INVALID;
        }
        rc = dl_nor_create(sensor_path, profile->sensor_nor_size);
        if (rc != DL_OK) {
            return rc;
        }
    }
    if (profile->main_nor || profile->has_sensor_nor) {
        rc = write_factory_target(have_nor ? nor_path : NULL,
                                  sensor_path[0] ? sensor_path : NULL,
                                  &profile->factory, identity, bind);
        if (rc != DL_OK) {
            return rc;
        }
    }

    if (profile->has_nand) {
        const dl_nand_spec *spec = &profile->nand;
        const char *resource = NULL;
        if (dl__path_join(nand_path, sizeof(nand_path), options->device_dir,
                          "nand.img") != DL_OK) {
            return DL_ERR_INVALID;
        }
        if (options->resource_override) {
            resource = options->resource_override;
        } else if (spec->resource_image && *spec->resource_image) {
            if (dl__path_join(resource_path, sizeof(resource_path),
                              options->firmware_dir, spec->resource_image) != DL_OK) {
                return DL_ERR_INVALID;
            }
            if (dl__file_size(resource_path) >= 0) {
                resource = resource_path;
            }
        }
        if (spec->has_system_pages && !resource) {
            /* yaffs2 /system from the package's main_res files */
            rc = dl_nand_build(nand_path, spec, NULL, options->firmware_dir);
        } else {
            rc = dl_nand_build(nand_path, spec, resource, options->firmware_dir);
        }
        if (rc != DL_OK) {
            return rc;
        }
        have_nand = 1;
        /* bind_config_dir may be NULL: the BindTemplate databases are
         * embedded in the library then. Only the raw-record profiles seed
         * /data; littlefs-factory devices (q66/p67/q63) keep their bind
         * state in /factory.prop and get an erased /data, as Mirage does. */
        if (options->seed_bind && bind && bind->bound &&
            profile->factory.format != DL_FACTORY_LITTLEFS) {
            rc = seed_bind(nand_path, spec, bind, options->bind_config_dir, ap_path);
            if (rc != DL_OK) {
                return rc;
            }
        }
    }

    if (profile->has_emmc) {
        if (dl__path_join(emmc_path, sizeof(emmc_path), options->device_dir,
                          "emmc.img") != DL_OK) {
            return DL_ERR_INVALID;
        }
        rc = dl_emmc_build(emmc_path, &profile->emmc, options->firmware_dir);
        if (rc != DL_OK) {
            return rc;
        }
        have_emmc = 1;
    }

    if (dl__path_join(identity_path, sizeof(identity_path), options->device_dir,
                      "identity.properties") != DL_OK) {
        return DL_ERR_INVALID;
    }
    rc = dl_identity_save(identity, bind, identity_path);
    if (rc != DL_OK) {
        return rc;
    }

    snprintf(bind_dir, sizeof(bind_dir), "%s", options->bind_config_dir ? options->bind_config_dir : "");
    (void)bind_dir;
    (void)have_emmc;

    if (verify) {
        memset(verify, 0, sizeof(*verify));
        if (have_nor || have_nand) {
            dl_verify_device(profile, have_nor ? nor_path : NULL,
                             have_nand ? nand_path : NULL, identity, verify);
        } else {
            verify->ok = 1;
            snprintf(verify->detail, sizeof(verify->detail), "images written");
        }
    }
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Verification                                                        */
/* ------------------------------------------------------------------ */

static void verify_append(dl_verify_result *r, const char *fmt, ...)
{
    size_t used = strlen(r->detail);
    va_list ap;
    if (used >= sizeof(r->detail) - 1) {
        return;
    }
    va_start(ap, fmt);
    vsnprintf(r->detail + used, sizeof(r->detail) - used, fmt, ap);
    va_end(ap);
}

int dl_verify_device(const dl_profile *profile, const char *nor_path,
                     const char *nand_path, const dl_identity *identity,
                     dl_verify_result *out)
{
    if (!profile || !out) {
        return DL_ERR_INVALID;
    }
    memset(out, 0, sizeof(*out));
    out->ok = 1;

    if (nor_path) {
        int64_t size = dl__file_size(nor_path);
        uint64_t factory_size = dl_factory_partition_size(&profile->factory);
        if (size < 0) {
            out->ok = 0;
            verify_append(out, "NOR: %s is missing\n", nor_path);
        } else {
            verify_append(out, "NOR: %lld bytes\n", (long long)size);
            if ((uint64_t)size < profile->factory.offset + factory_size) {
                out->ok = 0;
                verify_append(out, "NOR: too small for the factory partition\n");
            } else {
                dl_identity read;
                memset(&read, 0, sizeof(read));
                {
                    int rc = dl_factory_read(nor_path, &profile->factory, &read);
                    if (rc != DL_OK) {
                        out->ok = 0;
                        verify_append(out, "NOR: factory read failed: %s\n",
                                      dl_last_error());
                    } else {
                        verify_append(out, "factory: did=%s psn=%s mac=%s\n",
                                      read.did, read.psn, read.mac_bt);
                        if (identity) {
                            if (strcmp(read.did, identity->did) != 0 ||
                                strcmp(read.psn, identity->psn) != 0 ||
                                !mac_equal(read.mac_bt, identity->mac_bt)) {
                                out->ok = 0;
                                verify_append(out, "factory: identity mismatch\n");
                            }
                        }
                    }
                }
            }
        }
    }

    if (nand_path) {
        int64_t size = dl__file_size(nand_path);
        uint64_t want = dl_nand_image_size(&profile->nand);
        if (size < 0) {
            out->ok = 0;
            verify_append(out, "NAND: %s is missing\n", nand_path);
        } else {
            verify_append(out, "NAND: %lld bytes (expected %llu)\n",
                          (long long)size, (unsigned long long)want);
            if ((uint64_t)size != want) {
                out->ok = 0;
                verify_append(out, "NAND: size mismatch\n");
            }
            if (profile->nand.resource_pages > 0) {
                FILE *f = fopen(nand_path, "rb");
                char magic[8];
                long off = (long)((uint64_t)profile->nand.resource_first_page *
                                  (profile->nand.page_size + profile->nand.oob_size));
                if (f && fseek(f, off, SEEK_SET) == 0 &&
                    fread(magic, 1, 8, f) == 8 && !memcmp(magic, "-rom1fs-", 8)) {
                    verify_append(out, "NAND: resource romfs at page %u\n",
                                  (unsigned)profile->nand.resource_first_page);
                } else {
                    out->ok = 0;
                    verify_append(out, "NAND: no romfs at the resource partition start\n");
                }
                if (f) {
                    fclose(f);
                }
            }
        }
    }
    return DL_OK;
}
