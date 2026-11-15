/* devlib_factory — the factory partition: littlefs /factory.prop, the
 * Band 9/10 1292-byte raw record, and the REDMI Watch 6 256-byte p65 record.
 *
 * Byte layouts are copied from Mirage ImageBuilder.swift:
 *   littlefs   — format + write /factory.prop and /factory.prop.bkup
 *   rawInfo    — magic 0x9A55 @0, CRC-32(zero init) of bytes 12..1291 @4,
 *                MAC @0x8A(6), DID @0x92(9), PSN @0x14F(24)
 *   p65Record  — 256 B: MAC @8(6), DID big-endian u64 @14(8), key @22(16,
 *                padded '0'), PSN @39(20), FSN @63(14), SKU @77(9)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"
#include "devlib_lfs.h"

/* ------------------------------------------------------------------ */
/* littlefs factory partition                                          */
/* ------------------------------------------------------------------ */

static void lfs_geo(const dl_factory_part *part, dl_lfs_geo *geo)
{
    geo->block_size = part->block_size;
    geo->block_count = part->block_count;
    geo->read_size = part->io_size;
    geo->prog_size = part->io_size;
    geo->name_max = part->name_max ? part->name_max : 32;
    geo->disk_version = part->disk_version;
}

static int build_littlefs(const dl_factory_part *part, const dl_identity *identity,
                          const dl_bind *bind, void **out, size_t *out_size)
{
    size_t size = (size_t)dl_factory_partition_size(part);
    char text[4096];
    void *image;
    dl_lfs_geo geo;
    dl_lfs_file files[2];
    int rc;

    rc = dl_factory_text(identity, bind, text, sizeof(text));
    if (rc != DL_OK) {
        return rc;
    }
    image = malloc(size ? size : 1);
    if (!image) {
        dl_set_error("out of memory for the factory partition");
        return DL_ERR_IO;
    }
    memset(image, 0xFF, size);
    lfs_geo(part, &geo);
    files[0].path = "/factory.prop";
    files[0].data = text;
    files[0].size = strlen(text);
    files[1].path = "/factory.prop.bkup";
    files[1].data = text;
    files[1].size = strlen(text);
    rc = dl_lfs_build(image, &geo, files, 2);
    if (rc != 0) {
        free(image);
        dl_set_error("littlefs format failed (%d)", rc);
        return DL_ERR_LFS;
    }
    *out = image;
    *out_size = size;
    return DL_OK;
}

static int read_littlefs(const void *image, const dl_factory_part *part,
                         char *out, size_t cap)
{
    dl_lfs_geo geo;
    long n;

    lfs_geo(part, &geo);
    n = dl_lfs_read(image, &geo, "/factory.prop", out, cap ? cap - 1 : 0);
    if (n < 0) {
        dl_set_error(n == -84 ? "no factory filesystem in the partition"
                              : "littlefs read failed (%ld)", n);
        return n == -84 ? DL_ERR_FORMAT : DL_ERR_LFS;
    }
    if ((size_t)n < cap) {
        out[n] = 0;
    } else if (cap) {
        out[cap - 1] = 0;
    }
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Raw record (Band 9 / Band 10)                                       */
/* ------------------------------------------------------------------ */

enum {
    RAWINFO_SIZE = 1292,
    RAWINFO_CRC_OFFSET = 4,
    RAWINFO_BODY_OFFSET = 12,
    RAWINFO_BODY_END = 1291,
    RAWINFO_MAC_OFFSET = 0x8A,
    RAWINFO_MAC_LEN = 6,
    RAWINFO_DID_OFFSET = 0x92,
    RAWINFO_DID_LEN = 9,
    RAWINFO_PSN_OFFSET = 0x14F,
    RAWINFO_PSN_LEN = 24
};

static int build_rawinfo(const dl_factory_part *part, const dl_identity *identity,
                         void **out, size_t *out_size)
{
    size_t size = (size_t)dl_factory_partition_size(part);
    size_t alloc = size > RAWINFO_SIZE ? size : RAWINFO_SIZE;
    uint8_t *record;
    uint8_t mac[6];
    uint32_t crc;

    if (strlen(identity->did) > RAWINFO_DID_LEN) {
        dl_set_error("the raw factory record holds a device ID of at most %d decimal digits",
                     RAWINFO_DID_LEN);
        return DL_ERR_INVALID;
    }
    if (strlen(identity->psn) < 1 || strlen(identity->psn) > RAWINFO_PSN_LEN) {
        dl_set_error("the raw factory record holds a serial of 1-%d characters",
                     RAWINFO_PSN_LEN);
        return DL_ERR_INVALID;
    }
    if (!dl_mac_valid(identity->mac_bt)) {
        dl_set_error("the raw factory record needs a Bluetooth address");
        return DL_ERR_INVALID;
    }
    record = malloc(alloc);
    if (!record) {
        return DL_ERR_IO;
    }
    memset(record, 0xFF, alloc);
    {
        unsigned v[6];
        if (sscanf(identity->mac_bt, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2],
                   &v[3], &v[4], &v[5]) != 6) {
            free(record);
            return DL_ERR_INVALID;
        }
        for (int i = 0; i < 6; i++) {
            mac[i] = (uint8_t)v[i];
        }
    }
    memcpy(record + RAWINFO_MAC_OFFSET, mac, 6);
    memcpy(record + RAWINFO_DID_OFFSET, identity->did, strlen(identity->did));
    memcpy(record + RAWINFO_PSN_OFFSET, identity->psn, strlen(identity->psn));
    record[0] = 0x55;
    record[1] = 0x9A; /* magic 0x9A55 little endian */
    crc = dl_crc32_zero_init(record + RAWINFO_BODY_OFFSET,
                             RAWINFO_BODY_END - RAWINFO_BODY_OFFSET);
    dl__put_le32(record + RAWINFO_CRC_OFFSET, crc);
    *out = record;
    *out_size = alloc;
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* p65 record (REDMI Watch 6)                                          */
/* ------------------------------------------------------------------ */

enum {
    P65_SIZE = 256,
    P65_MAC_OFFSET = 8,
    P65_MAC_LEN = 6,
    P65_DID_OFFSET = 14,
    P65_DID_LEN = 8,
    P65_KEY_OFFSET = 22,
    P65_KEY_LEN = 16,
    P65_PSN_OFFSET = 39,
    P65_PSN_LEN = 20,
    P65_FSN_OFFSET = 63,
    P65_FSN_LEN = 14,
    P65_SKU_OFFSET = 77,
    P65_SKU_LEN = 9
};

static void put_field(uint8_t *record, size_t offset, size_t len,
                      const char *value, size_t value_len, uint8_t pad)
{
    size_t n = value_len < len ? value_len : len;
    memcpy(record + offset, value, n);
    if (n < len) {
        memset(record + offset + n, pad, len - n);
    }
}

static int build_p65(const dl_factory_part *part, const dl_identity *identity,
                     void **out, size_t *out_size)
{
    size_t size = (size_t)dl_factory_partition_size(part);
    size_t alloc = size > P65_SIZE ? size : P65_SIZE;
    uint8_t *record;
    uint64_t did;
    char *end = NULL;

    did = strtoull(identity->did, &end, 10);
    if (!*identity->did || !end || *end) {
        dl_set_error("the p65 factory record needs a numeric device ID");
        return DL_ERR_INVALID;
    }
    if (!*identity->psn) {
        dl_set_error("the p65 factory record needs a serial (ro.factory.psn)");
        return DL_ERR_INVALID;
    }
    if (!dl_mac_valid(identity->mac_bt)) {
        dl_set_error("the p65 factory record needs a Bluetooth address (XX:XX:XX:XX:XX:XX)");
        return DL_ERR_INVALID;
    }
    record = calloc(1, alloc);
    if (!record) {
        return DL_ERR_IO;
    }
    {
        unsigned v[6];
        uint8_t mac[6];
        if (sscanf(identity->mac_bt, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2],
                   &v[3], &v[4], &v[5]) != 6) {
            free(record);
            return DL_ERR_INVALID;
        }
        for (int i = 0; i < 6; i++) {
            mac[i] = (uint8_t)v[i];
        }
        memcpy(record + P65_MAC_OFFSET, mac, 6);
    }
    /* DID as a big-endian u64 */
    for (int i = 0; i < 8; i++) {
        record[P65_DID_OFFSET + i] = (uint8_t)(did >> (56 - 8 * i));
    }
    put_field(record, P65_KEY_OFFSET, P65_KEY_LEN,
              identity->key[0] ? identity->key : "0123456789ABCDEF",
              strlen(identity->key[0] ? identity->key : "0123456789ABCDEF"), '0');
    put_field(record, P65_PSN_OFFSET, P65_PSN_LEN, identity->psn,
              strlen(identity->psn), 0);
    put_field(record, P65_FSN_OFFSET, P65_FSN_LEN,
              identity->fsn[0] ? identity->fsn : "QEMU1502P00001",
              strlen(identity->fsn[0] ? identity->fsn : "QEMU1502P00001"), 0);
    put_field(record, P65_SKU_OFFSET, P65_SKU_LEN,
              identity->sku[0] ? identity->sku : "M2509W1",
              strlen(identity->sku[0] ? identity->sku : "M2509W1"), 0);
    *out = record;
    *out_size = alloc;
    return DL_OK;
}

static int read_p65(const uint8_t *record, size_t size,
                    dl_identity *out, char *props, size_t prop_cap)
{
    char psn[P65_PSN_LEN + 1], fsn[P65_FSN_LEN + 1], sku[P65_SKU_LEN + 1];
    char key[P65_KEY_LEN + 1];
    uint64_t did = 0;

    if (size < P65_SIZE) {
        return DL_ERR_FORMAT;
    }
    memset(out, 0, sizeof(*out));
    for (int i = 0; i < P65_DID_LEN; i++) {
        did = (did << 8) | record[P65_DID_OFFSET + i];
    }
    snprintf(out->did, sizeof(out->did), "%llu", (unsigned long long)did);
    memcpy(psn, record + P65_PSN_OFFSET, P65_PSN_LEN);
    psn[P65_PSN_LEN] = 0;
    while (strlen(psn) && (psn[strlen(psn) - 1] == 0)) {
        psn[strlen(psn) - 1] = 0;
    }
    snprintf(out->psn, sizeof(out->psn), "%s", psn);
    snprintf(out->mac_bt, sizeof(out->mac_bt), "%02X:%02X:%02X:%02X:%02X:%02X",
             record[P65_MAC_OFFSET], record[P65_MAC_OFFSET + 1],
             record[P65_MAC_OFFSET + 2], record[P65_MAC_OFFSET + 3],
             record[P65_MAC_OFFSET + 4], record[P65_MAC_OFFSET + 5]);
    memcpy(key, record + P65_KEY_OFFSET, P65_KEY_LEN);
    key[P65_KEY_LEN] = 0;
    snprintf(out->key, sizeof(out->key), "%s", key);
    memcpy(fsn, record + P65_FSN_OFFSET, P65_FSN_LEN);
    fsn[P65_FSN_LEN] = 0;
    snprintf(out->fsn, sizeof(out->fsn), "%s", fsn);
    memcpy(sku, record + P65_SKU_OFFSET, P65_SKU_LEN);
    sku[P65_SKU_LEN] = 0;
    snprintf(out->sku, sizeof(out->sku), "%s", sku);
    if (props && prop_cap) {
        snprintf(props, prop_cap,
                 "ro.factory.did=%s\nro.factory.psn=%s\nro.factory.mac_bt=%s\n"
                 "ro.factory.key=%s\nro.factory.fsn=%s\nro.factory.sku=%s\n",
                 out->did, out->psn, out->mac_bt, out->key, out->fsn, out->sku);
    }
    return DL_OK;
}

static int read_rawinfo(const uint8_t *record, size_t size, dl_identity *out,
                        char *props, size_t prop_cap)
{
    char did[RAWINFO_DID_LEN + 1], psn[RAWINFO_PSN_LEN + 1];

    if (size < RAWINFO_SIZE) {
        return DL_ERR_FORMAT;
    }
    if (record[0] != 0x55 || record[1] != 0x9A) {
        dl_set_error("no factory record in the partition (magic 0x9A55 missing)");
        return DL_ERR_FORMAT;
    }
    memset(out, 0, sizeof(*out));
    memcpy(did, record + RAWINFO_DID_OFFSET, RAWINFO_DID_LEN);
    did[RAWINFO_DID_LEN] = 0;
    snprintf(out->did, sizeof(out->did), "%s", did);
    memcpy(psn, record + RAWINFO_PSN_OFFSET, RAWINFO_PSN_LEN);
    psn[RAWINFO_PSN_LEN] = 0;
    snprintf(out->psn, sizeof(out->psn), "%s", psn);
    snprintf(out->mac_bt, sizeof(out->mac_bt), "%02X:%02X:%02X:%02X:%02X:%02X",
             record[RAWINFO_MAC_OFFSET], record[RAWINFO_MAC_OFFSET + 1],
             record[RAWINFO_MAC_OFFSET + 2], record[RAWINFO_MAC_OFFSET + 3],
             record[RAWINFO_MAC_OFFSET + 4], record[RAWINFO_MAC_OFFSET + 5]);
    if (props && prop_cap) {
        snprintf(props, prop_cap,
                 "ro.factory.did=%s\nro.factory.psn=%s\nro.factory.mac_bt=%s\n",
                 out->did, out->psn, out->mac_bt);
    }
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Public factory API                                                  */
/* ------------------------------------------------------------------ */

int dl_factory_build(const dl_factory_part *part, const dl_identity *identity,
                     const dl_bind *bind, void **out, size_t *out_size)
{
    int rc;

    if (!part || !identity || !out || !out_size) {
        return DL_ERR_INVALID;
    }
    *out = NULL;
    *out_size = 0;
    rc = dl_identity_validate(identity);
    if (rc != DL_OK) {
        return rc;
    }
    if (dl_bind_validate(bind) != DL_OK) {
        return DL_ERR_INVALID;
    }
    switch (part->format) {
    case DL_FACTORY_LITTLEFS:
        return build_littlefs(part, identity, bind, out, out_size);
    case DL_FACTORY_RAWINFO:
        return build_rawinfo(part, identity, out, out_size);
    case DL_FACTORY_P65RECORD:
        return build_p65(part, identity, out, out_size);
    default:
        dl_set_error("unknown factory format %d", (int)part->format);
        return DL_ERR_INVALID;
    }
}

int dl_factory_write(const char *image_path, const dl_factory_part *part,
                     const dl_identity *identity, const dl_bind *bind)
{
    void *data = NULL;
    size_t size = 0;
    int rc;

    rc = dl_factory_build(part, identity, bind, &data, &size);
    if (rc != DL_OK) {
        return rc;
    }
    if ((uint64_t)dl__file_size(image_path) < part->offset + size) {
        free(data);
        dl_set_error("%s is too small for the factory partition", image_path);
        return DL_ERR_NOSPACE;
    }
    {
        FILE *f = fopen(image_path, "r+b");
        if (!f) {
            free(data);
            dl_set_error("cannot open %s for update", image_path);
            return DL_ERR_IO;
        }
        if (fseek(f, (long)part->offset, SEEK_SET) != 0 ||
            fwrite(data, 1, size, f) != size) {
            fclose(f);
            free(data);
            dl_set_error("cannot write the factory partition to %s", image_path);
            return DL_ERR_IO;
        }
        fclose(f);
    }
    free(data);
    return DL_OK;
}

int dl_factory_read(const char *image_path, const dl_factory_part *part,
                    dl_identity *out)
{
    char props[4096];
    int rc = dl_factory_read_text(image_path, part, props, sizeof(props));
    if (rc != DL_OK) {
        return rc;
    }
    /* This path only fills the identity: parse the property text. */
    memset(out, 0, sizeof(*out));
    {
        char *line = props;
        while (line && *line) {
            char *nl = strchr(line, '\n');
            if (nl) {
                *nl = 0;
            }
            char *eq = strchr(line, '=');
            if (eq) {
                *eq = 0;
                if (!strcmp(line, "ro.factory.did")) {
                    snprintf(out->did, sizeof(out->did), "%s", eq + 1);
                } else if (!strcmp(line, "ro.factory.psn")) {
                    snprintf(out->psn, sizeof(out->psn), "%s", eq + 1);
                } else if (!strcmp(line, "ro.factory.mac_bt")) {
                    snprintf(out->mac_bt, sizeof(out->mac_bt), "%s", eq + 1);
                } else if (!strcmp(line, "ro.factory.sku")) {
                    snprintf(out->sku, sizeof(out->sku), "%s", eq + 1);
                } else if (!strcmp(line, "ro.factory.key")) {
                    snprintf(out->key, sizeof(out->key), "%s", eq + 1);
                } else if (!strcmp(line, "ro.factory.fsn")) {
                    snprintf(out->fsn, sizeof(out->fsn), "%s", eq + 1);
                }
            }
            line = nl ? nl + 1 : NULL;
        }
    }
    return DL_OK;
}

int dl_factory_read_text(const char *image_path, const dl_factory_part *part,
                         char *out, size_t cap)
{
    size_t size;
    uint8_t *region;
    int rc;

    if (!image_path || !part || !out || !cap) {
        return DL_ERR_INVALID;
    }
    out[0] = 0;
    size = (size_t)dl_factory_partition_size(part);
    if (size == 0) {
        return DL_ERR_INVALID;
    }
    region = malloc(size);
    if (!region) {
        return DL_ERR_IO;
    }
    {
        FILE *f = fopen(image_path, "rb");
        if (!f) {
            free(region);
            dl_set_error("cannot open %s", image_path);
            return DL_ERR_IO;
        }
        if (fseek(f, (long)part->offset, SEEK_SET) != 0 ||
            fread(region, 1, size, f) != size) {
            fclose(f);
            free(region);
            dl_set_error("%s is too small for the factory partition", image_path);
            return DL_ERR_IO;
        }
        fclose(f);
    }
    switch (part->format) {
    case DL_FACTORY_LITTLEFS:
        rc = read_littlefs(region, part, out, cap);
        break;
    case DL_FACTORY_RAWINFO: {
        dl_identity id;
        rc = read_rawinfo(region, size, &id, out, cap);
        break;
    }
    case DL_FACTORY_P65RECORD: {
        dl_identity id;
        rc = read_p65(region, size, &id, out, cap);
        break;
    }
    default:
        rc = DL_ERR_INVALID;
        break;
    }
    free(region);
    return rc;
}
