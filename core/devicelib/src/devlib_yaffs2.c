/* devlib_yaffs2 — yaffs2 /system partition writer (Band 9 / Band 10 /
 * Band 9 Pro) and the bind databases Mirage seeds into /data.
 *
 * Page format copied from Mirage Files/Yaffs2.swift: 2048-byte chunks with
 * inband tags in the last 16 bytes (seq, obj id, chunk id, nbytes), 64
 * chunks per block. The object header chunk is a 2032-byte yaffs object
 * header whose tag carries the parent in the chunk-id field.
 */
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "devlib_bind_resources.h"
#include "devlib_internal.h"
#include "devlib_yaffs2.h"

#define YAFFS_CHUNK_DATA 2032
#define YAFFS_FIRST_SEQ 0x1001u
#define YAFFS_FIRST_OBJ 257u
#define YAFFS_ROOT 1u
#define YAFFS_TYPE_FILE 1u
#define YAFFS_TYPE_DIR 3u
#define YAFFS_EXTRA_HEADER 0x80000000u
#define YAFFS_TIME 946684800u
#define YAFFS_CHUNKS_PER_BLOCK 64

static uint32_t g_next_oid;

/* ------------------------------------------------------------------ */
/* Object header                                                       */
/* ------------------------------------------------------------------ */

static void yaffs_header(uint8_t h[YAFFS_CHUNK_DATA], uint32_t type,
                         uint32_t parent, const char *name, uint32_t size,
                         uint32_t mode)
{
    size_t n = strlen(name);
    memset(h, 0xFF, YAFFS_CHUNK_DATA);
    if (n > 255) {
        n = 255;
    }
    dl__put_le32(h + 0, type);
    dl__put_le32(h + 4, parent);
    memset(h + 10, 0, 256);
    memcpy(h + 10, name, n);
    dl__put_le32(h + 268, mode);
    dl__put_le32(h + 272, 0);
    dl__put_le32(h + 276, 0);
    dl__put_le32(h + 280, YAFFS_TIME);
    dl__put_le32(h + 284, YAFFS_TIME);
    dl__put_le32(h + 288, YAFFS_TIME);
    dl__put_le32(h + 292, size);
    dl__put_le32(h + 296, 0xFFFFFFFFu);
    dl__put_le32(h + 460, 0);
    dl__put_le32(h + 464, YAFFS_TIME);
    dl__put_le32(h + 468, 0);
    dl__put_le32(h + 472, YAFFS_TIME);
    dl__put_le32(h + 476, 0);
    dl__put_le32(h + 480, YAFFS_TIME);
    dl__put_le32(h + 484, 0);
    dl__put_le32(h + 488, 0);
    dl__put_le32(h + 496, 0);
    dl__put_le32(h + 504, 0);
    dl__put_le32(h + 508, 0);
}

/* ------------------------------------------------------------------ */
/* Page emission                                                       */
/* ------------------------------------------------------------------ */

int dl_yaffs2_emit(const dl_yaffs2_file *files, int count, dl_yaffs2_emit_fn emit,
                   void *ctx)
{
    uint64_t index = 0;
    int rc = DL_OK;

    for (int i = 0; i < count && rc == DL_OK; i++) {
        const dl_yaffs2_file *f = &files[i];
        uint32_t oid = f->oid;
        uint32_t parent = f->parent;
        if (f->is_dir) {
            uint8_t h[YAFFS_CHUNK_DATA];
            uint8_t page[2048];
            uint8_t tag[16];
            yaffs_header(h, YAFFS_TYPE_DIR, parent, f->name, 0xFFFFFFFFu, 0755);
            memset(page, 0xFF, sizeof(page));
            memcpy(page, h, YAFFS_CHUNK_DATA);
            {
                uint32_t seq = YAFFS_FIRST_SEQ + (uint32_t)(index / YAFFS_CHUNKS_PER_BLOCK);
                dl__put_le32(tag + 0, seq);
                dl__put_le32(tag + 4, oid | (YAFFS_TYPE_DIR << 28));
                dl__put_le32(tag + 8, YAFFS_EXTRA_HEADER | parent);
                dl__put_le32(tag + 12, 0);
            }
            memcpy(page + 2032, tag, 16);
            rc = emit(ctx, page);
            index++;
        } else {
            uint32_t size = (uint32_t)f->size;
            uint8_t h[YAFFS_CHUNK_DATA];
            uint8_t page[2048];
            uint8_t tag[16];
            yaffs_header(h, YAFFS_TYPE_FILE, parent, f->name, size, 0644);
            memset(page, 0xFF, sizeof(page));
            memcpy(page, h, YAFFS_CHUNK_DATA);
            {
                uint32_t seq = YAFFS_FIRST_SEQ + (uint32_t)(index / YAFFS_CHUNKS_PER_BLOCK);
                dl__put_le32(tag + 0, seq);
                dl__put_le32(tag + 4, oid | (YAFFS_TYPE_FILE << 28));
                dl__put_le32(tag + 8, YAFFS_EXTRA_HEADER | parent);
                dl__put_le32(tag + 12, size);
            }
            memcpy(page + 2032, tag, 16);
            rc = emit(ctx, page);
            index++;
            if (rc != DL_OK) {
                break;
            }
            {
                FILE *in = fopen(f->path, "rb");
                uint32_t cid = 1;
                if (!in) {
                    dl_set_error("cannot open %s", f->path);
                    return DL_ERR_IO;
                }
                for (;;) {
                    size_t n = fread(page, 1, YAFFS_CHUNK_DATA, in);
                    if (n == 0) {
                        break;
                    }
                    memset(page + n, 0xFF, YAFFS_CHUNK_DATA - n);
                    {
                        uint32_t seq =
                            YAFFS_FIRST_SEQ + (uint32_t)(index / YAFFS_CHUNKS_PER_BLOCK);
                        dl__put_le32(tag + 0, seq);
                        dl__put_le32(tag + 4, oid);
                        dl__put_le32(tag + 8, cid);
                        dl__put_le32(tag + 12, (uint32_t)n);
                    }
                    memcpy(page + 2032, tag, 16);
                    rc = emit(ctx, page);
                    index++;
                    cid++;
                    if (rc != DL_OK) {
                        break;
                    }
                }
                fclose(in);
            }
        }
    }
    return rc;
}

int dl_yaffs2_page_count(const dl_yaffs2_file *files, int count)
{
    int pages = 0;
    for (int i = 0; i < count; i++) {
        pages += 1;
        if (!files[i].is_dir) {
            pages += (int)((files[i].size + YAFFS_CHUNK_DATA - 1) / YAFFS_CHUNK_DATA);
        }
    }
    return pages;
}

/* ------------------------------------------------------------------ */
/* /system tree: a "system" directory, or the package's main_res files */
/* ------------------------------------------------------------------ */

typedef struct {
    dl_yaffs2_file *files;
    int count;
    int cap;
} file_list;

static int list_push(file_list *l, const dl_yaffs2_file *f)
{
    if (l->count == l->cap) {
        int ncap = l->cap ? l->cap * 2 : 16;
        dl_yaffs2_file *grown = realloc(l->files, (size_t)ncap * sizeof(*grown));
        if (!grown) {
            return DL_ERR_IO;
        }
        l->files = grown;
        l->cap = ncap;
    }
    l->files[l->count++] = *f;
    return DL_OK;
}

static int cmp_names(const void *a, const void *b)
{
    return strcmp(*(const char *const *)a, *(const char *const *)b);
}

static int walk_tree(const char *dir, uint32_t parent, file_list *l)
{
    DIR *d = opendir(dir);
    struct dirent *ent;
    char **names = NULL;
    int nnames = 0, cap = 0;
    int rc = DL_OK;

    if (!d) {
        dl_set_error("cannot open %s", dir);
        return DL_ERR_IO;
    }
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_name[0] == '.') {
            continue;
        }
        if (nnames == cap) {
            int ncap = cap ? cap * 2 : 16;
            char **grown = realloc(names, (size_t)ncap * sizeof(*grown));
            if (!grown) {
                rc = DL_ERR_IO;
                goto out;
            }
            names = grown;
            cap = ncap;
        }
        {
            size_t len = strlen(ent->d_name) + 1;
            names[nnames] = malloc(len);
            if (!names[nnames]) {
                rc = DL_ERR_IO;
                goto out;
            }
            memcpy(names[nnames], ent->d_name, len);
        }
        nnames++;
    }
    qsort(names, (size_t)nnames, sizeof(*names), cmp_names);
    for (int i = 0; i < nnames && rc == DL_OK; i++) {
        char path[4096];
        struct stat st;
        if (dl__path_join(path, sizeof(path), dir, names[i]) != DL_OK) {
            rc = DL_ERR_INVALID;
            break;
        }
        if (stat(path, &st) != 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            dl_yaffs2_file f;
            memset(&f, 0, sizeof(f));
            snprintf(f.name, sizeof(f.name), "%s", names[i]);
            snprintf(f.path, sizeof(f.path), "%s", path);
            f.is_dir = 1;
            f.oid = g_next_oid++;
            f.parent = parent;
            rc = list_push(l, &f);
            if (rc == DL_OK) {
                rc = walk_tree(path, f.oid, l);
            }
        } else if (S_ISREG(st.st_mode)) {
            dl_yaffs2_file f;
            memset(&f, 0, sizeof(f));
            snprintf(f.name, sizeof(f.name), "%s", names[i]);
            snprintf(f.path, sizeof(f.path), "%s", path);
            f.size = (uint64_t)st.st_size;
            f.oid = g_next_oid++;
            f.parent = parent;
            rc = list_push(l, &f);
        }
    }
out:
    for (int i = 0; i < nnames; i++) {
        free(names[i]);
    }
    free(names);
    closedir(d);
    return rc;
}

/* Parses ota.json's "location_path" values as root-level files. */
static void add_main_res(const char *json_path, const char *fw_dir, file_list *l)
{
    void *data = NULL;
    size_t size = 0;
    char *text;

    if (dl__read_file(json_path, &data, &size) != DL_OK) {
        return;
    }
    text = malloc(size + 1);
    if (!text) {
        free(data);
        return;
    }
    memcpy(text, data, size);
    text[size] = 0;
    free(data);
    {
        const char *p = text;
        while ((p = strstr(p, "\"location_path\"")) != NULL) {
            const char *colon = strchr(p, ':');
            const char *q1, *q2;
            if (!colon) {
                break;
            }
            q1 = strchr(colon, '"');
            if (!q1) {
                break;
            }
            q2 = strchr(q1 + 1, '"');
            if (!q2) {
                break;
            }
            {
                char name[256];
                size_t n = (size_t)(q2 - q1 - 1);
                if (n > 0 && n < sizeof(name)) {
                    char path[4096];
                    memcpy(name, q1 + 1, n);
                    name[n] = 0;
                    if (dl__path_join(path, sizeof(path), fw_dir, name) == DL_OK &&
                        dl__file_size(path) >= 0) {
                        dl_yaffs2_file f;
                        memset(&f, 0, sizeof(f));
                        snprintf(f.name, sizeof(f.name), "%s", name);
                        snprintf(f.path, sizeof(f.path), "%s", path);
                        f.size = (uint64_t)dl__file_size(path);
                        f.oid = g_next_oid++;
                        f.parent = YAFFS_ROOT;
                        list_push(l, &f);
                    }
                }
            }
            p = q2 + 1;
        }
    }
    free(text);
}

int dl_yaffs2_system_files(const char *firmware_dir, dl_yaffs2_file **out, int *count)
{
    file_list l;
    char sys_dir[4096];
    char manifest[4096];
    int rc;

    if (!firmware_dir || !out || !count) {
        return DL_ERR_INVALID;
    }
    memset(&l, 0, sizeof(l));
    g_next_oid = YAFFS_FIRST_OBJ;
    dl__path_join(sys_dir, sizeof(sys_dir), firmware_dir, "system");
    if (dl__is_dir(sys_dir)) {
        rc = walk_tree(sys_dir, YAFFS_ROOT, &l);
        if (rc != DL_OK) {
            free(l.files);
            return rc;
        }
    } else {
        dl__path_join(manifest, sizeof(manifest), firmware_dir, "ota.json");
        if (dl__file_size(manifest) >= 0) {
            add_main_res(manifest, firmware_dir, &l);
        } else {
            /* fallback: flat non-firmware files in the directory */
            DIR *d = opendir(firmware_dir);
            struct dirent *ent;
            if (d) {
                while ((ent = readdir(d)) != NULL) {
                    if (ent->d_name[0] == '.' || !strncmp(ent->d_name, "vela_", 5) ||
                        !strcmp(ent->d_name, "ota.sh") || !strcmp(ent->d_name, "ota.json") ||
                        !strcmp(ent->d_name, "META-INF")) {
                        continue;
                    }
                    {
                        char path[4096];
                        if (dl__path_join(path, sizeof(path), firmware_dir, ent->d_name) != DL_OK) {
                            continue;
                        }
                        if (dl__file_size(path) < 0 || dl__is_dir(path)) {
                            continue;
                        }
                        dl_yaffs2_file f;
                        memset(&f, 0, sizeof(f));
                        snprintf(f.name, sizeof(f.name), "%s", ent->d_name);
                        snprintf(f.path, sizeof(f.path), "%s", path);
                        f.size = (uint64_t)dl__file_size(path);
                        f.oid = g_next_oid++;
                        f.parent = YAFFS_ROOT;
                        list_push(&l, &f);
                    }
                }
                closedir(d);
            }
        }
    }
    *out = l.files;
    *count = l.count;
    return DL_OK;
}

void dl_yaffs2_files_free(dl_yaffs2_file *files)
{
    free(files);
}

/* ------------------------------------------------------------------ */
/* Bind seed (Mirage BindSeed.apply)                                   */
/* ------------------------------------------------------------------ */

typedef struct {
    char name[64];
    char path[4096];
    uint64_t size;
    int is_dir;
    uint32_t oid;
    uint32_t parent;
    void *data;      /* owned for in-memory databases */
    size_t data_size;
} bind_object;

/* Loads a BindTemplate database: from config_dir when given, else from the
 * templates embedded in the library. */
static int load_template(const char *config_dir, const char *name,
                         void **data, size_t *size)
{
    if (config_dir && *config_dir) {
        char path[4096];
        if (dl__path_join(path, sizeof(path), config_dir, name) != DL_OK) {
            return DL_ERR_INVALID;
        }
        return dl__read_file(path, data, size);
    }
    {
        const void *builtin = dl_builtin_bind_template(name, size);
        if (!builtin) {
            dl_set_error("no built-in bind template %s", name);
            return DL_ERR_NOT_FOUND;
        }
        *data = malloc(*size ? *size : 1);
        if (!*data) {
            return DL_ERR_IO;
        }
        memcpy(*data, builtin, *size);
        return DL_OK;
    }
}

int dl_seed_bind_build(const char *config_dir, const char *bt_database,
                       const dl_bind *bind, const char *out_db_files_dir,
                       uint8_t (**pages)[2048], size_t *page_count)
{
    bind_object objs[5];
    void *persist = NULL, *record = NULL, *bt_data = NULL;
    size_t persist_size = 0, record_size = 0, bt_size = 0;
    int rc = DL_ERR_IO;
    size_t npages;

    (void)out_db_files_dir;
    if (!bind || !bind->bound || !pages || !page_count) {
        return DL_ERR_INVALID;
    }
    memset(objs, 0, sizeof(objs));

    /* templates (from config_dir, or embedded in the library) */
    {
        if (load_template(config_dir, "persist.db", &persist, &persist_size) != DL_OK) {
            return DL_ERR_NOT_FOUND;
        }
        if (load_template(config_dir, "miwear_record.db", &record, &record_size) != DL_OK) {
            free(persist);
            return DL_ERR_NOT_FOUND;
        }
        if (load_template(config_dir, bt_database ? bt_database : "bt_storage.db",
                          &bt_data, &bt_size) != DL_OK) {
            free(persist);
            free(record);
            return DL_ERR_NOT_FOUND;
        }
    }

    /* persist.db: replace the framed KEY\\0VALUE\\0 records in place */
    {
        struct {
            const char *name;
            const char *tpl;
            const char *value;
        } keys[5];
        keys[0].name = "persist.miwear_token_key";
        keys[0].tpl = "d8c3ac9607212d61610745828d0b564b";
        keys[0].value = bind->auth_key;
        keys[1].name = "persist.miwear_user_id";
        keys[1].tpl = "10000001";
        keys[1].value = bind->user_id;
        keys[2].name = "persist.miwear_beacon_key";
        keys[2].tpl = "8d3f350abe295942e35ea1b13ea06f72";
        keys[2].value = bind->beacon_key;
        keys[3].name = "persist.miwear_ir_key";
        keys[3].tpl = "d461619c47c561f3c40e494a1b79f4fc";
        keys[3].value = bind->ir_key;
        keys[4].name = "persist.miwear_seqnum";
        keys[4].tpl = "1";
        keys[4].value = bind->seqnum;
        for (int k = 0; k < 5; k++) {
            size_t nlen = strlen(keys[k].name), tlen = strlen(keys[k].tpl);
            size_t vlen = strlen(keys[k].value);
            uint8_t *needle = malloc(nlen + 1 + tlen + 1);
            int found = 0;
            if (!needle) {
                rc = DL_ERR_IO;
                goto out;
            }
            if (vlen != tlen) {
                free(needle);
                dl_set_error("%s must be %u characters", keys[k].name, (unsigned)tlen);
                rc = DL_ERR_INVALID;
                goto out;
            }
            memcpy(needle, keys[k].name, nlen);
            needle[nlen] = 0;
            memcpy(needle + nlen + 1, keys[k].tpl, tlen);
            needle[nlen + 1 + tlen] = 0;
            for (size_t off = 0; off + nlen + 1 + tlen + 1 <= persist_size; off++) {
                if (memcmp((uint8_t *)persist + off, needle, nlen + 1 + tlen + 1) == 0) {
                    memcpy((uint8_t *)persist + off + nlen + 1, keys[k].value, tlen);
                    found = 1;
                    break;
                }
            }
            free(needle);
            if (!found) {
                dl_set_error("the bind template has no %s", keys[k].name);
                rc = DL_ERR_FORMAT;
                goto out;
            }
        }
    }

    /* miwear_record.db: raw 16-byte values after their names */
    {
        struct {
            const char *tpl;
            const char *value;
        } raw[3];
        raw[0].tpl = "d8c3ac9607212d61610745828d0b564b";
        raw[0].value = bind->auth_key;
        raw[1].tpl = "8d3f350abe295942e35ea1b13ea06f72";
        raw[1].value = bind->beacon_key;
        raw[2].tpl = "d461619c47c561f3c40e494a1b79f4fc";
        raw[2].value = bind->ir_key;
        for (int k = 0; k < 3; k++) {
            uint8_t oldb[16], newb[16];
            int found = 0;
            for (int i = 0; i < 16; i++) {
                unsigned ov, nv;
                if (sscanf(raw[k].tpl + 2 * i, "%2x", &ov) != 1 ||
                    sscanf(raw[k].value + 2 * i, "%2x", &nv) != 1) {
                    rc = DL_ERR_INVALID;
                    goto out;
                }
                oldb[i] = (uint8_t)ov;
                newb[i] = (uint8_t)nv;
            }
            for (size_t off = 0; off + 16 <= record_size; off++) {
                if (memcmp((uint8_t *)record + off, oldb, 16) == 0) {
                    memcpy((uint8_t *)record + off, newb, 16);
                    found = 1;
                    break;
                }
            }
            if (!found) {
                dl_set_error("the record template does not carry the bond key");
                rc = DL_ERR_FORMAT;
                goto out;
            }
        }
    }

    /* objects, in Mirage BindSeed order: misc/bt directories and the files */
    snprintf(objs[0].name, sizeof(objs[0].name), "misc");
    objs[0].is_dir = 1;
    objs[0].oid = 258;
    objs[0].parent = 1;
    snprintf(objs[1].name, sizeof(objs[1].name), "bt");
    objs[1].is_dir = 1;
    objs[1].oid = 259;
    objs[1].parent = 258;
    snprintf(objs[2].name, sizeof(objs[2].name), "persist.db");
    objs[2].data = persist;
    objs[2].data_size = persist_size;
    objs[2].oid = 257;
    objs[2].parent = 1;
    snprintf(objs[3].name, sizeof(objs[3].name), "miwear_record.db");
    objs[3].data = record;
    objs[3].data_size = record_size;
    objs[3].oid = 260;
    objs[3].parent = 259;
    snprintf(objs[4].name, sizeof(objs[4].name), "%s", bt_database ? bt_database : "bt_storage.db");
    objs[4].data = bt_data;
    objs[4].data_size = bt_size;
    objs[4].oid = 261;
    objs[4].parent = 259;

    {
        /* count pages */
        npages = 0;
        for (int i = 0; i < 5; i++) {
            npages += 1;
            if (!objs[i].is_dir) {
                npages += (objs[i].data_size + YAFFS_CHUNK_DATA - 1) / YAFFS_CHUNK_DATA;
            }
        }
        uint8_t(*mem)[2048] = calloc(npages, 2048);
        if (!mem) {
            rc = DL_ERR_IO;
            goto out;
        }
        {
            /* emit by hand, same layout as dl_yaffs2_emit but from memory */
            size_t index = 0;
            for (int i = 0; i < 5; i++) {
                bind_object *o = &objs[i];
                uint8_t page[2048], tag[16], h[YAFFS_CHUNK_DATA];
                if (o->is_dir) {
                    yaffs_header(h, YAFFS_TYPE_DIR, o->parent, o->name, 0xFFFFFFFFu, 0755);
                    memset(page, 0xFF, sizeof(page));
                    memcpy(page, h, YAFFS_CHUNK_DATA);
                    dl__put_le32(tag + 0, YAFFS_FIRST_SEQ + (uint32_t)(index / 64));
                    dl__put_le32(tag + 4, o->oid | (YAFFS_TYPE_DIR << 28));
                    dl__put_le32(tag + 8, YAFFS_EXTRA_HEADER | o->parent);
                    dl__put_le32(tag + 12, 0);
                    memcpy(page + 2032, tag, 16);
                    memcpy(mem[index], page, 2048);
                    index++;
                } else {
                    yaffs_header(h, YAFFS_TYPE_FILE, o->parent, o->name,
                                 (uint32_t)o->data_size, 0644);
                    memset(page, 0xFF, sizeof(page));
                    memcpy(page, h, YAFFS_CHUNK_DATA);
                    dl__put_le32(tag + 0, YAFFS_FIRST_SEQ + (uint32_t)(index / 64));
                    dl__put_le32(tag + 4, o->oid | (YAFFS_TYPE_FILE << 28));
                    dl__put_le32(tag + 8, YAFFS_EXTRA_HEADER | o->parent);
                    dl__put_le32(tag + 12, (uint32_t)o->data_size);
                    memcpy(page + 2032, tag, 16);
                    memcpy(mem[index], page, 2048);
                    index++;
                    for (size_t off = 0; off < o->data_size; off += YAFFS_CHUNK_DATA) {
                        size_t n = o->data_size - off;
                        if (n > YAFFS_CHUNK_DATA) {
                            n = YAFFS_CHUNK_DATA;
                        }
                        memset(page, 0xFF, sizeof(page));
                        memcpy(page, (uint8_t *)o->data + off, n);
                        dl__put_le32(tag + 0, YAFFS_FIRST_SEQ + (uint32_t)(index / 64));
                        dl__put_le32(tag + 4, o->oid);
                        dl__put_le32(tag + 8, (uint32_t)(off / YAFFS_CHUNK_DATA) + 1);
                        dl__put_le32(tag + 12, (uint32_t)n);
                        memcpy(page + 2032, tag, 16);
                        memcpy(mem[index], page, 2048);
                        index++;
                    }
                }
            }
            *pages = mem;
            *page_count = index;
            rc = DL_OK;
        }
    }
out:
    free(persist);
    free(record);
    free(bt_data);
    return rc;
}

void dl_seed_bind_free(uint8_t (*pages)[2048])
{
    free(pages);
}
