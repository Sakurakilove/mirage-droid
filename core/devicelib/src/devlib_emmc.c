/* devlib_emmc — GPT eMMC image builder (watch profiles, e.g. q63).
 *
 * Layout copied from Mirage ImageBuilder.buildEMMC: 512-byte sectors, every
 * romfs partition 1 MiB aligned and filled from vela_<name>.bin, then the
 * blank partitions the firmware formats itself. Partition names are UTF-16LE
 * in the GPT; the partition type is the EFI basic-data GUID and each unique
 * GUID is a name-based UUIDv5 (same namespace as Mirage).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"

#define SECTOR 512
#define ALIGN_MIB (1u << 20)

static const uint8_t GPT_NAMESPACE[16] = {
    0x6F, 0x8A, 0x3C, 0x8E, 0x1B, 0x70, 0x4D, 0x2E,
    0x9A, 0x5E, 0x0E, 0x3B, 0x7D, 0x1C, 0x17, 0x00
};
static const uint8_t GPT_BASIC_DATA[16] = {
    0xEB, 0xD0, 0xA0, 0xA2, 0xB9, 0xE5, 0x44, 0x33,
    0x87, 0xC0, 0x68, 0xB6, 0xB7, 0x26, 0x99, 0xC7
};

typedef struct {
    char name[64];
    uint64_t offset;
    uint64_t size;
    char source[4096];    /* empty = blank partition */
} gpt_part;

/* UUIDv5 = SHA-1(namespace || name), then version/variant bits. */
static void uuid5(const char *name, uint8_t out[16])
{
    size_t nlen = strlen(name);
    size_t total = sizeof(GPT_NAMESPACE) + nlen;
    uint8_t *buf = malloc(total);
    uint8_t digest[20];
    if (!buf) {
        memset(out, 0, 16);
        return;
    }
    memcpy(buf, GPT_NAMESPACE, sizeof(GPT_NAMESPACE));
    memcpy(buf + sizeof(GPT_NAMESPACE), name, nlen);
    dl__sha1(buf, total, digest);
    free(buf);
    memcpy(out, digest, 16);
    out[6] = (uint8_t)((out[6] & 0x0F) | 0x50);
    out[8] = (uint8_t)((out[8] & 0x3F) | 0x80);
}

/* Mixed-endian GUID bytes (Python's uuid.bytes_le). */
static void guid_le(const uint8_t uuid[16], uint8_t out[16])
{
    out[0] = uuid[3];
    out[1] = uuid[2];
    out[2] = uuid[1];
    out[3] = uuid[0];
    out[4] = uuid[5];
    out[5] = uuid[4];
    out[6] = uuid[7];
    out[7] = uuid[6];
    memcpy(out + 8, uuid + 8, 8);
}

static void gpt_entry(uint8_t *e, const char *name, uint64_t first, uint64_t last)
{
    uint8_t u[16];
    memset(e, 0, 128);
    guid_le(GPT_BASIC_DATA, e);
    uuid5(name, u);
    guid_le(u, e + 16);
    dl__put_le64(e + 32, first);
    dl__put_le64(e + 40, last);
    /* attributes 0, then the UTF-16LE name at 56 */
    for (size_t i = 0; i < strlen(name) && i < 36; i++) {
        e[56 + 2 * i] = (uint8_t)name[i];
        e[56 + 2 * i + 1] = 0;
    }
}

static void gpt_header(uint8_t *h, uint64_t my, uint64_t alt, uint64_t first,
                       uint64_t last, uint64_t entries, uint32_t table_crc,
                       const uint8_t disk_uuid[16])
{
    uint8_t u[16];
    uint32_t hcrc;

    memset(h, 0, SECTOR);
    memcpy(h, "EFI PART", 8);
    dl__put_le32(h + 8, 0x00010000u);
    dl__put_le32(h + 12, 92);
    dl__put_le32(h + 16, 0); /* header CRC, filled below */
    dl__put_le32(h + 20, 0);
    dl__put_le64(h + 24, my);
    dl__put_le64(h + 32, alt);
    dl__put_le64(h + 40, first);
    dl__put_le64(h + 48, last);
    guid_le(disk_uuid, u);
    memcpy(h + 56, u, 16);
    dl__put_le64(h + 72, entries);
    dl__put_le32(h + 80, 128);
    dl__put_le32(h + 84, 128);
    dl__put_le32(h + 88, table_crc);
    hcrc = dl_crc32(h, 92);
    dl__put_le32(h + 16, hcrc);
}

static uint64_t align_up(uint64_t v, uint64_t a)
{
    return (v + a - 1) / a * a;
}

static int build_layout(const dl_emmc_spec *spec, const char *firmware_dir,
                        gpt_part *parts, int *count, uint64_t *total)
{
    uint64_t off = ALIGN_MIB;
    int n = 0;

    for (int i = 0; i < spec->romfs_count; i++) {
        char path[4096];
        char name[128];
        int64_t size;
        snprintf(name, sizeof(name), "vela_%s.bin", spec->romfs[i]);
        if (dl__path_join(path, sizeof(path), firmware_dir, name) != DL_OK) {
            return DL_ERR_INVALID;
        }
        size = dl__file_size(path);
        if (size < 0) {
            dl_set_error("the firmware has no %s", name);
            return DL_ERR_NOT_FOUND;
        }
        snprintf(parts[n].name, sizeof(parts[n].name), "%s", spec->romfs[i]);
        parts[n].offset = off;
        parts[n].size = align_up((uint64_t)size, ALIGN_MIB);
        snprintf(parts[n].source, sizeof(parts[n].source), "%s", path);
        off += parts[n].size;
        n++;
    }
    for (int i = 0; i < spec->blank_count; i++) {
        snprintf(parts[n].name, sizeof(parts[n].name), "%s", spec->blank[i].name);
        parts[n].offset = off;
        parts[n].size = (uint64_t)spec->blank[i].mib << 20;
        parts[n].source[0] = 0;
        off += parts[n].size;
        n++;
    }
    *count = n;
    *total = off;
    return DL_OK;
}

int dl_emmc_build(const char *path, const dl_emmc_spec *spec, const char *firmware_dir)
{
    gpt_part parts[32];
    int count = 0;
    uint64_t used = 0;
    uint64_t size, nsect;
    FILE *f = NULL;
    uint8_t table[128 * 128];
    uint32_t tcrc;
    uint8_t disk_uuid[16];
    int rc;

    if (!path || !spec || !firmware_dir) {
        return DL_ERR_INVALID;
    }
    memset(parts, 0, sizeof(parts));
    rc = build_layout(spec, firmware_dir, parts, &count, &used);
    if (rc != DL_OK) {
        goto out;
    }
    size = (uint64_t)spec->size_gib << 30;
    nsect = size / SECTOR;
    if (used > (nsect - 34) * SECTOR) {
        dl_set_error("the partitions need %llu MiB, more than the %d GiB eMMC",
                     (unsigned long long)(used >> 20), spec->size_gib);
        rc = DL_ERR_NOSPACE;
        goto out;
    }

    memset(table, 0, sizeof(table));
    for (int i = 0; i < count; i++) {
        char name[64];
        snprintf(name, sizeof(name), "%s", parts[i].name);
        gpt_entry(table + 128 * i, name, parts[i].offset / SECTOR,
                  (parts[i].offset + parts[i].size) / SECTOR - 1);
    }
    tcrc = dl_crc32(table, sizeof(table));
    uuid5("disk", disk_uuid);

    f = fopen(path, "wb+");
    if (!f) {
        dl_set_error("cannot create %s", path);
        rc = DL_ERR_IO;
        goto out;
    }
    /* sparse image: reserve the full size without writing zeroes */
    if (fseek(f, (long)(size - 1), SEEK_SET) != 0 || fputc(0, f) == EOF) {
        dl_set_error("cannot size %s", path);
        rc = DL_ERR_IO;
        goto out;
    }
    {
        uint8_t mbr[SECTOR], hdr[SECTOR];
        uint32_t last_lba = (uint32_t)(nsect - 1 > 0xFFFFFFFFull ? 0xFFFFFFFFull : nsect - 1);
        memset(mbr, 0, sizeof(mbr));
        mbr[446] = 0;
        mbr[447] = 0;
        mbr[448] = 2;
        mbr[449] = 0;
        mbr[450] = 0xEE;
        mbr[451] = 0xFF;
        mbr[452] = 0xFF;
        mbr[453] = 0xFF;
        dl__put_le32(mbr + 454, 1);
        dl__put_le32(mbr + 458, last_lba);
        mbr[510] = 0x55;
        mbr[511] = 0xAA;
        if (fseek(f, 0, SEEK_SET) != 0 || fwrite(mbr, 1, SECTOR, f) != SECTOR) {
            rc = DL_ERR_IO;
            goto out;
        }
        gpt_header(hdr, 1, nsect - 1, 34, nsect - 34, 2, tcrc, disk_uuid);
        if (fwrite(hdr, 1, SECTOR, f) != SECTOR) {
            rc = DL_ERR_IO;
            goto out;
        }
        if (fwrite(table, 1, sizeof(table), f) != sizeof(table)) {
            rc = DL_ERR_IO;
            goto out;
        }
        /* backup table + header */
        if (fseek(f, (long)((nsect - 33) * SECTOR), SEEK_SET) != 0 ||
            fwrite(table, 1, sizeof(table), f) != sizeof(table)) {
            rc = DL_ERR_IO;
            goto out;
        }
        gpt_header(hdr, nsect - 1, 1, 34, nsect - 34, nsect - 33, tcrc, disk_uuid);
        if (fwrite(hdr, 1, SECTOR, f) != SECTOR) {
            rc = DL_ERR_IO;
            goto out;
        }
    }

    for (int i = 0; i < count; i++) {
        if (!parts[i].source[0]) {
            continue;
        }
        {
            FILE *src = fopen(parts[i].source, "rb");
            uint8_t buf[1 << 16];
            uint64_t written = 0;
            size_t n;
            if (!src) {
                dl_set_error("cannot open %s", parts[i].source);
                rc = DL_ERR_IO;
                goto out;
            }
            if (fseek(f, (long)parts[i].offset, SEEK_SET) != 0) {
                fclose(src);
                rc = DL_ERR_IO;
                goto out;
            }
            while ((n = fread(buf, 1, sizeof(buf), src)) > 0) {
                if (fwrite(buf, 1, n, f) != n) {
                    fclose(src);
                    rc = DL_ERR_IO;
                    goto out;
                }
                written += n;
            }
            fclose(src);
            if (written > parts[i].size) {
                rc = DL_ERR_NOSPACE;
                goto out;
            }
        }
    }
    rc = DL_OK;
out:
    if (f) {
        fclose(f);
    }
    return rc;
}

int dl_emmc_list_partitions(const char *image_path, char *out, size_t cap)
{
    FILE *f;
    uint8_t hdr[SECTOR];
    uint64_t lba, count;
    uint8_t *table = NULL;
    size_t used = 0;

    if (!image_path || !out || !cap) {
        return DL_ERR_INVALID;
    }
    out[0] = 0;
    f = fopen(image_path, "rb");
    if (!f) {
        return DL_ERR_IO;
    }
    if (fseek(f, SECTOR, SEEK_SET) != 0 || fread(hdr, 1, SECTOR, f) != SECTOR ||
        memcmp(hdr, "EFI PART", 8) != 0) {
        fclose(f);
        dl_set_error("%s has no GPT", image_path);
        return DL_ERR_FORMAT;
    }
    lba = dl__le64(hdr + 72);
    count = dl__le32(hdr + 80);
    if (count == 0 || count > 4096) {
        fclose(f);
        return DL_ERR_FORMAT;
    }
    table = calloc((size_t)count, 128);
    if (!table) {
        fclose(f);
        return DL_ERR_IO;
    }
    if (fseek(f, (long)(lba * SECTOR), SEEK_SET) != 0 ||
        fread(table, 128, (size_t)count, f) != (size_t)count) {
        free(table);
        fclose(f);
        return DL_ERR_IO;
    }
    fclose(f);
    for (uint64_t i = 0; i < count; i++) {
        const uint8_t *e = table + 128 * i;
        uint64_t first = dl__le64(e + 32);
        uint64_t last = dl__le64(e + 40);
        char name[72];
        size_t n = 0;
        if (first == 0) {
            continue;
        }
        for (size_t k = 0; k < 36; k++) {
            char c = (char)e[56 + 2 * k];
            if (!c) {
                break;
            }
            name[n++] = c;
        }
        name[n] = 0;
        if (used < cap) {
            int w = snprintf(out + used, cap - used,
                             "%s offset=%llu size=%llu\n", name,
                             (unsigned long long)(first * SECTOR),
                             (unsigned long long)((last - first + 1) * SECTOR));
            if (w > 0 && (size_t)w < cap - used) {
                used += (size_t)w;
            }
        }
    }
    free(table);
    return DL_OK;
}
