/* devlib_romfs — read-only NuttX romfs images (the resource partition).
 *
 * Ported from Mirage Files/RomFS.swift: "-rom1fs-" header, big-endian
 * 16-byte aligned inode headers {next|type, spec, size, checksum, name}.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"

#define ROMFS_INODE_HDR 16
#define ROMFS_ALIGN(n) (((n) + 15) & ~15)

typedef struct {
    FILE *f;
    uint64_t size;
    uint32_t first_entry;
} romfs;

typedef struct {
    char name[256];
    uint32_t next;
    uint32_t spec;
    uint32_t size;
    uint32_t data_offset;
    int is_dir;
} node;

static int rf_read(romfs *r, uint32_t off, void *buf, size_t n)
{
    if ((uint64_t)off + n > r->size) {
        dl_set_error("romfs: read past the end of the image");
        return DL_ERR_FORMAT;
    }
    if (fseek(r->f, (long)off, SEEK_SET) != 0 || fread(buf, 1, n, r->f) != n) {
        dl_set_error("romfs: read error");
        return DL_ERR_IO;
    }
    return DL_OK;
}

static int rf_open(const char *path, romfs *r)
{
    uint8_t head[512];
    size_t got;
    memset(r, 0, sizeof(*r));
    r->f = fopen(path, "rb");
    if (!r->f) {
        dl_set_error("cannot open %s", path);
        return DL_ERR_IO;
    }
    memset(head, 0, sizeof(head));
    got = fread(head, 1, sizeof(head), r->f);
    if (got < 32 || memcmp(head, "-rom1fs-", 8) != 0) {
        fclose(r->f);
        r->f = NULL;
        dl_set_error("%s is not a romfs image", path);
        return DL_ERR_FORMAT;
    }
    r->size = (uint64_t)dl__file_size(path);
    {
        size_t name_end = 16;
        while (name_end < got && head[name_end] != 0) {
            name_end++;
        }
        r->first_entry = (uint32_t)ROMFS_ALIGN(name_end + 1);
    }
    return DL_OK;
}

static void rf_close(romfs *r)
{
    if (r->f) {
        fclose(r->f);
        r->f = NULL;
    }
}

int dl_romfs_check(const char *image_path)
{
    romfs r;
    int rc = rf_open(image_path, &r);
    if (rc == DL_OK) {
        rf_close(&r);
    }
    return rc;
}

static int rf_node(romfs *r, uint32_t off, node *n)
{
    uint8_t h[ROMFS_INODE_HDR + 256];
    size_t name_len = 0;
    uint32_t next, spec, size;
    int rc;

    memset(n, 0, sizeof(*n));
    rc = rf_read(r, off, h, sizeof(h));
    if (rc != DL_OK) {
        return rc;
    }
    next = dl__be32(h + 0);
    spec = dl__be32(h + 4);
    size = dl__be32(h + 8);
    while (name_len < 255 && h[16 + name_len] != 0) {
        name_len++;
    }
    memcpy(n->name, h + 16, name_len);
    n->name[name_len] = 0;
    n->next = next & ~15u;
    n->spec = spec;
    n->size = size;
    n->data_offset = off + (uint32_t)ROMFS_ALIGN(ROMFS_INODE_HDR + name_len + 1);
    {
        int type = (int)(next & 7);
        if (type == 0) { /* hard link: follow the target header */
            uint8_t t[ROMFS_INODE_HDR];
            node target;
            rc = rf_read(r, spec, t, sizeof(t));
            if (rc != DL_OK) {
                return rc;
            }
            type = (int)(dl__be32(t) & 7);
            rc = rf_node(r, spec, &target);
            if (rc != DL_OK) {
                return rc;
            }
            n->size = target.size;
            n->data_offset = target.data_offset;
            n->spec = target.spec;
        }
        n->is_dir = type == 1;
    }
    return DL_OK;
}

static int rf_children(romfs *r, uint32_t first, dl_romfs_entry **out, size_t *count)
{
    dl_romfs_entry *entries = NULL;
    size_t n = 0, cap = 0;
    uint32_t off = first;
    int guard = 0;

    while (off != 0 && guard++ < 100000) {
        node nd;
        int rc = rf_node(r, off, &nd);
        if (rc != DL_OK) {
            free(entries);
            return rc;
        }
        if (strcmp(nd.name, ".") && strcmp(nd.name, "..")) {
            if (n == cap) {
                size_t ncap = cap ? cap * 2 : 8;
                dl_romfs_entry *grown = realloc(entries, ncap * sizeof(*entries));
                if (!grown) {
                    free(entries);
                    return DL_ERR_IO;
                }
                entries = grown;
                cap = ncap;
            }
            snprintf(entries[n].name, sizeof(entries[n].name), "%s", nd.name);
            entries[n].is_dir = nd.is_dir;
            entries[n].size = nd.is_dir ? 0 : nd.size;
            entries[n].data_offset = nd.data_offset;
            n++;
        }
        off = nd.next;
    }
    *out = entries;
    *count = n;
    return DL_OK;
}

static int rf_lookup(romfs *r, const char *path, node *out)
{
    node cur;
    memset(&cur, 0, sizeof(cur));
    cur.is_dir = 1;
    cur.spec = r->first_entry;
    if (!path || !*path || !strcmp(path, "/")) {
        *out = cur;
        return DL_OK;
    }
    {
        const char *p = path;
        while (*p == '/') {
            p++;
        }
        while (*p) {
            char comp[256];
            size_t n = 0;
            int found = 0;
            while (p[n] && p[n] != '/' && n < sizeof(comp) - 1) {
                comp[n] = p[n];
                n++;
            }
            comp[n] = 0;
            p += n;
            while (*p == '/') {
                p++;
            }
            if (!cur.is_dir) {
                return DL_ERR_NOT_FOUND;
            }
            {
                dl_romfs_entry *entries = NULL;
                size_t count = 0;
                int rc = rf_children(r, cur.spec, &entries, &count);
                if (rc != DL_OK) {
                    return rc;
                }
                for (size_t i = 0; i < count; i++) {
                    if (!strcmp(entries[i].name, comp)) {
                        node nd;
                        uint32_t off = cur.spec;
                        /* find the inode offset of this child again */
                        uint32_t walk = cur.spec;
                        int guard = 0;
                        while (walk != 0 && guard++ < 100000) {
                            node cand;
                            rc = rf_node(r, walk, &cand);
                            if (rc != DL_OK) {
                                free(entries);
                                return rc;
                            }
                            if (!strcmp(cand.name, comp)) {
                                nd = cand;
                                found = 1;
                                break;
                            }
                            walk = cand.next;
                        }
                        (void)off;
                        if (found) {
                            cur = nd;
                        }
                        break;
                    }
                }
                free(entries);
            }
            if (!found) {
                return DL_ERR_NOT_FOUND;
            }
        }
    }
    *out = cur;
    return DL_OK;
}

static int rf_stat_impl(const char *image_path, const char *path, dl_romfs_stat *out)
{
    romfs r;
    node nd;
    int rc = rf_open(image_path, &r);
    if (rc != DL_OK) {
        return rc;
    }
    rc = rf_lookup(&r, path, &nd);
    rf_close(&r);
    if (rc != DL_OK) {
        return rc;
    }
    out->is_dir = nd.is_dir;
    out->size = nd.is_dir ? 0 : nd.size;
    out->data_offset = nd.data_offset;
    return DL_OK;
}

int dl_romfs_lookup(const char *image_path, const char *path, dl_romfs_stat *out)
{
    if (!image_path || !path || !out) {
        return DL_ERR_INVALID;
    }
    return rf_stat_impl(image_path, path, out);
}

int dl_romfs_list(const char *image_path, const char *path,
                  dl_romfs_entry **out, size_t *count)
{
    romfs r;
    node nd;
    int rc;

    if (!image_path || !out || !count) {
        return DL_ERR_INVALID;
    }
    *out = NULL;
    *count = 0;
    rc = rf_open(image_path, &r);
    if (rc != DL_OK) {
        return rc;
    }
    rc = rf_lookup(&r, path ? path : "/", &nd);
    if (rc == DL_OK && !nd.is_dir) {
        rc = DL_ERR_INVALID;
    }
    if (rc == DL_OK) {
        rc = rf_children(&r, nd.spec, out, count);
    }
    rf_close(&r);
    return rc;
}

void dl_romfs_entries_free(dl_romfs_entry *entries)
{
    free(entries);
}

int dl_romfs_read(const char *image_path, const char *path, void **out, size_t *size)
{
    romfs r;
    node nd;
    int rc;
    void *buf;

    if (!image_path || !path || !out) {
        return DL_ERR_INVALID;
    }
    rc = rf_open(image_path, &r);
    if (rc != DL_OK) {
        return rc;
    }
    rc = rf_lookup(&r, path, &nd);
    if (rc != DL_OK) {
        rf_close(&r);
        return rc;
    }
    if (nd.is_dir) {
        rf_close(&r);
        return DL_ERR_INVALID;
    }
    buf = malloc(nd.size ? nd.size : 1);
    if (!buf) {
        rf_close(&r);
        return DL_ERR_IO;
    }
    rc = rf_read(&r, nd.data_offset, buf, nd.size);
    rf_close(&r);
    if (rc != DL_OK) {
        free(buf);
        return rc;
    }
    *out = buf;
    if (size) {
        *size = nd.size;
    }
    return DL_OK;
}
