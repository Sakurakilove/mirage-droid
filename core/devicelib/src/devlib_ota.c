/* devlib_ota — official OTA package import and vela_ap.bin boot info. */
#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "devlib_internal.h"

/* ------------------------------------------------------------------ */
/* vela_ap.bin info                                                    */
/* ------------------------------------------------------------------ */

/* Reads the ASCII value of "KEY=" up to NUL / CR / LF, trimmed. */
static int value_after(const uint8_t *data, size_t size, const char *key,
                       char *out, size_t cap)
{
    size_t klen = strlen(key);
    for (size_t i = 0; i + klen <= size; i++) {
        if (memcmp(data + i, key, klen) != 0) {
            continue;
        }
        size_t start = i + klen, end = start;
        while (end < size && end - start < 128 && data[end] != 0 &&
               data[end] != '\n' && data[end] != '\r') {
            end++;
        }
        while (start < end && (data[start] == ' ' || data[start] == '\t')) {
            start++;
        }
        while (end > start && (data[end - 1] == ' ' || data[end - 1] == '\t')) {
            end--;
        }
        size_t n = end - start;
        if (n >= cap) {
            n = cap - 1;
        }
        memcpy(out, data + start, n);
        out[n] = 0;
        return n > 0;
    }
    return 0;
}

static int parse_u64(const char *s, uint64_t *out)
{
    char *end = NULL;
    unsigned long long v;
    if (!s || !*s) {
        return 0;
    }
    v = strtoull(s, &end, 0);
    if (end == s) {
        return 0;
    }
    *out = (uint64_t)v;
    return 1;
}

int dl_ap_parse_info(const void *data, size_t size, dl_ap_info *out)
{
    const uint8_t *p = data;
    char tmp[128];

    if (!data || !out) {
        return DL_ERR_INVALID;
    }
    memset(out, 0, sizeof(*out));
    if (value_after(p, size, "CHIP=", out->chip, sizeof(out->chip))) {
        /* ok */
    }
    if (value_after(p, size, "FLASH_SIZE=", tmp, sizeof(tmp))) {
        out->has_flash_size = parse_u64(tmp, &out->flash_size);
    }
    if (value_after(p, size, "OTA_CODE_OFFSET=", tmp, sizeof(tmp))) {
        out->has_ota_code_offset = parse_u64(tmp, &out->ota_code_offset);
    }
    value_after(p, size, "BUILD_DATE=", out->build_date, sizeof(out->build_date));
    value_after(p, size, "ro.build.version=", out->version, sizeof(out->version));
    value_after(p, size, "ro.build.customer_version=", out->customer_version,
                sizeof(out->customer_version));

    /* product names: miwear.watch.* / mijia.watch.* / lchz.watch.* */
    static const char *prefixes[] = { "miwear.watch.", "mijia.watch.", "lchz.watch." };
    for (size_t pi = 0; pi < sizeof(prefixes) / sizeof(prefixes[0]); pi++) {
        const char *prefix = prefixes[pi];
        size_t plen = strlen(prefix);
        for (size_t i = 0; i + plen < size; i++) {
            if (memcmp(p + i, prefix, plen) != 0) {
                continue;
            }
            size_t start = i, end = i + plen;
            while (end < size && end - (i + plen) < 16) {
                uint8_t c = p[end];
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) {
                    break;
                }
                end++;
            }
            if (end == i + plen) {
                continue;
            }
            {
                char name[40];
                size_t n = end - start;
                if (n >= sizeof(name)) {
                    n = sizeof(name) - 1;
                }
                memcpy(name, p + start, n);
                name[n] = 0;
                int dup = 0;
                for (int k = 0; k < out->product_count; k++) {
                    if (!strcmp(out->products[k], name)) {
                        dup = 1;
                        break;
                    }
                }
                if (!dup && out->product_count < 16) {
                    snprintf(out->products[out->product_count],
                             sizeof(out->products[0]), "%s", name);
                    out->product_count++;
                }
            }
        }
    }
    return out->chip[0] ? DL_OK : DL_ERR_FORMAT;
}

int dl_ap_read_info(const char *ap_path, dl_ap_info *out)
{
    void *data = NULL;
    size_t size = 0;
    int rc;

    rc = dl__read_file(ap_path, &data, &size);
    if (rc != DL_OK) {
        return rc;
    }
    rc = dl_ap_parse_info(data, size, out);
    free(data);
    if (rc != DL_OK) {
        dl_set_error("%s has no firmware boot info (CHIP=)", ap_path);
    }
    return rc;
}

/* ------------------------------------------------------------------ */
/* Package name parsing: <product>_v<version>_full_<hash>              */
/* ------------------------------------------------------------------ */

static int parse_package_name(const char *name, char *product, size_t cap,
                              char *version, size_t vcap)
{
    char base[512];
    char *parts[16];
    int count = 0;
    size_t len;

    if (!name) {
        return 0;
    }
    snprintf(base, sizeof(base), "%s", name);
    /* strip directories */
    {
        char *slash = strrchr(base, '/');
        if (slash) {
            memmove(base, slash + 1, strlen(slash + 1) + 1);
        }
    }
    len = strlen(base);
    if (len > 4 && !strcmp(base + len - 4, ".bin")) {
        base[len - 4] = 0;
    } else if (len > 4 && !strcmp(base + len - 4, ".zip")) {
        base[len - 4] = 0;
    }
    parts[count++] = base;
    for (char *p = base; *p; p++) {
        if (*p == '_') {
            *p = 0;
            if (count < 16) {
                parts[count++] = p + 1;
            }
        }
    }
    if (count < 4 || parts[1][0] != 'v') {
        return 0;
    }
    {
        int dots = 0;
        for (char *p = parts[0]; *p; p++) {
            dots += *p == '.';
        }
        if (dots != 2) {
            return 0;
        }
    }
    snprintf(product, cap, "%s", parts[0]);
    snprintf(version, vcap, "%s", parts[1] + 1);
    return 1;
}

/* ------------------------------------------------------------------ */
/* Kept-name filter (Mirage FirmwareImporter.keptNames)                */
/* ------------------------------------------------------------------ */

typedef struct {
    char names[128][256];
    int count;
} name_set;

static int set_has(const name_set *set, const char *name)
{
    for (int i = 0; i < set->count; i++) {
        if (!strcmp(set->names[i], name)) {
            return 1;
        }
    }
    return 0;
}

static void set_add(name_set *set, const char *name)
{
    if (!name || !*name || set_has(set, name)) {
        return;
    }
    if (set->count >= (int)(sizeof(set->names) / sizeof(set->names[0]))) {
        return;
    }
    snprintf(set->names[set->count], sizeof(set->names[0]), "%s", name);
    set->count++;
}

static int has_suffix(const char *s, const char *suffix)
{
    size_t a = strlen(s), b = strlen(suffix);
    return a >= b && !strcmp(s + a - b, suffix);
}

static int has_prefix(const char *s, const char *prefix)
{
    return !strncmp(s, prefix, strlen(prefix));
}

/* Reads ota.json and adds the main_res file locations. */
static void add_ota_json_files(const char *json_path, name_set *kept)
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

    /* A tiny JSON walker: every "section_type": "main_res" section's
     * "location_path" is a package file. Instead of a full parser, scan for
     * location_path values and accept them all: the package only names its
     * payload images here, and dl__copy skips missing ones. */
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
                    memcpy(name, q1 + 1, n);
                    name[n] = 0;
                    set_add(kept, name);
                }
            }
            p = q2 + 1;
        }
    }
    free(text);
}

/* Builds the kept-name set for either a package directory or a zip entry
 * list. */
static void build_kept_set(const char *ota_json_path, const char **zip_names,
                           size_t zip_count, name_set *kept)
{
    memset(kept, 0, sizeof(*kept));
    set_add(kept, "ota.json");
    set_add(kept, "ota.sh");
    add_ota_json_files(ota_json_path, kept);
    for (size_t i = 0; i < zip_count; i++) {
        const char *n = zip_names[i];
        if (has_prefix(n, "vela_") && has_suffix(n, ".bin")) {
            set_add(kept, n);
        }
    }
}

static int should_keep(const name_set *kept, const char *name)
{
    if (set_has(kept, name)) {
        return 1;
    }
    if (has_prefix(name, "META-INF/")) {
        return 1;
    }
    if (has_prefix(name, "system/")) {
        return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* Import                                                              */
/* ------------------------------------------------------------------ */

static int import_from_zip(const char *zip_path, const char *out_dir,
                           dl_import_report *report)
{
    dl_zip_list list;
    name_set kept;
    const char **names = NULL;
    char ota_json[4096];
    int rc;

    rc = dl_zip_scan(zip_path, &list);
    if (rc != DL_OK) {
        return rc;
    }
    names = calloc(list.count ? list.count : 1, sizeof(*names));
    if (!names) {
        dl_zip_list_free(&list);
        return DL_ERR_IO;
    }
    for (size_t i = 0; i < list.count; i++) {
        names[i] = list.entries[i].name;
    }
    dl__path_join(ota_json, sizeof(ota_json), out_dir, "ota.json");
    /* ota.json names the package's main_res payload files: unpack it first
     * so the kept-name set can include them. */
    if (dl_zip_entry_size(zip_path, "ota.json", NULL) == DL_OK) {
        (void)dl_zip_extract(zip_path, "ota.json", ota_json, NULL);
    }
    build_kept_set(ota_json, names, list.count, &kept);

    for (size_t i = 0; i < list.count; i++) {
        const char *name = list.entries[i].name;
        char out_path[4096];
        uint64_t size = 0;
        if (!should_keep(&kept, name)) {
            continue;
        }
        /* skip directory entries (they were filtered out of the list) */
        if (dl__path_join(out_path, sizeof(out_path), out_dir, name) != DL_OK) {
            rc = DL_ERR_INVALID;
            break;
        }
        /* make sure nested paths exist */
        {
            char dir[4096];
            snprintf(dir, sizeof(dir), "%s", out_path);
            char *slash = strrchr(dir, '/');
            if (slash) {
                *slash = 0;
                if (dl__mkdirs(dir) != DL_OK) {
                    rc = DL_ERR_IO;
                    break;
                }
            }
        }
        rc = dl_zip_extract(zip_path, name, out_path, &size);
        if (rc != DL_OK) {
            break;
        }
        if (report && report->file_count < 32) {
            snprintf(report->files[report->file_count].name,
                     sizeof(report->files[0].name), "%s", name);
            report->files[report->file_count].size = size;
            report->file_count++;
            report->total_bytes += size;
        }
    }
    free(names);
    dl_zip_list_free(&list);
    return rc;
}

static int find_package_root(const char *dir, char *out, size_t cap)
{
    DIR *d;
    struct dirent *ent;
    char candidate[4096];

    if (dl__path_join(candidate, sizeof(candidate), dir, "vela_ap.bin") == DL_OK &&
        dl__file_size(candidate) >= 0) {
        snprintf(out, cap, "%s", dir);
        return DL_OK;
    }
    d = opendir(dir);
    if (!d) {
        dl_set_error("cannot open %s", dir);
        return DL_ERR_IO;
    }
    int hits = 0;
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_name[0] == '.') {
            continue;
        }
        char sub[4096], ap[4096];
        if (dl__path_join(sub, sizeof(sub), dir, ent->d_name) != DL_OK) {
            continue;
        }
        if (!dl__is_dir(sub)) {
            continue;
        }
        if (dl__path_join(ap, sizeof(ap), sub, "vela_ap.bin") == DL_OK &&
            dl__file_size(ap) >= 0) {
            snprintf(out, cap, "%s", sub);
            hits++;
        }
    }
    closedir(d);
    if (hits != 1) {
        dl_set_error("no firmware package in %s (expected vela_ap.bin)", dir);
        return DL_ERR_NOT_FOUND;
    }
    return DL_OK;
}

static int import_from_dir(const char *source, const char *out_dir,
                           dl_import_report *report)
{
    char root[4096];
    char ota_json[4096];
    name_set kept;
    int rc;

    rc = find_package_root(source, root, sizeof(root));
    if (rc != DL_OK) {
        return rc;
    }
    dl__path_join(ota_json, sizeof(ota_json), root, "ota.json");
    build_kept_set(ota_json, NULL, 0, &kept);

    {
        /* keep vela_*.bin from the directory as well */
        DIR *d = opendir(root);
        struct dirent *ent;
        if (!d) {
            return DL_ERR_IO;
        }
        while ((ent = readdir(d)) != NULL) {
            if (has_prefix(ent->d_name, "vela_") && has_suffix(ent->d_name, ".bin")) {
                set_add(&kept, ent->d_name);
            }
        }
        closedir(d);
    }

    for (int i = 0; i < kept.count; i++) {
        char from[4096], to[4096], dir[4096];
        uint64_t copied = 0;
        if (!should_keep(&kept, kept.names[i])) {
            continue;
        }
        dl__path_join(from, sizeof(from), root, kept.names[i]);
        dl__path_join(to, sizeof(to), out_dir, kept.names[i]);
        snprintf(dir, sizeof(dir), "%s", to);
        char *slash = strrchr(dir, '/');
        if (slash) {
            *slash = 0;
            dl__mkdirs(dir);
        }
        if (dl__file_size(from) < 0) {
            continue; /* ota.json may name optional files */
        }
        rc = dl__copy_file(from, to, &copied);
        if (rc != DL_OK) {
            return rc;
        }
        if (report && report->file_count < 32) {
            snprintf(report->files[report->file_count].name,
                     sizeof(report->files[0].name), "%s", kept.names[i]);
            report->files[report->file_count].size = copied;
            report->file_count++;
            report->total_bytes += copied;
        }
    }
    return DL_OK;
}

int dl_ota_import(const char *source, const char *out_dir, dl_import_report *report)
{
    dl_ap_info info;
    char ap_path[4096];
    char base[512];
    int rc;

    if (!source || !out_dir) {
        return DL_ERR_INVALID;
    }
    memset(report, 0, sizeof(*report));
    if (dl__mkdirs(out_dir) != DL_OK) {
        return DL_ERR_IO;
    }

    if (dl__is_dir(source)) {
        rc = import_from_dir(source, out_dir, report);
    } else {
        rc = import_from_zip(source, out_dir, report);
    }
    if (rc != DL_OK) {
        return rc;
    }

    /* The importer names the firmware: <product>_v<version> from the
     * package file name, falling back to the AP image's boot info. */
    dl__path_join(ap_path, sizeof(ap_path), out_dir, "vela_ap.bin");
    rc = dl_ap_read_info(ap_path, &info);
    if (rc != DL_OK) {
        return rc;
    }
    {
        char product[64] = "", version[64] = "";
        const char *slash = strrchr(source, '/');
        snprintf(base, sizeof(base), "%s", slash ? slash + 1 : source);
        if (parse_package_name(base, product, sizeof(product), version, sizeof(version))) {
            snprintf(report->product, sizeof(report->product), "%s", product);
            snprintf(report->version, sizeof(report->version), "%s", version);
        } else {
            snprintf(report->product, sizeof(report->product), "%s",
                     info.product_count ? info.products[0] : "");
            snprintf(report->version, sizeof(report->version), "%s",
                     info.version[0] ? info.version : "unknown");
        }
    }
    snprintf(report->chip, sizeof(report->chip), "%s", info.chip);
    report->flash_size = info.has_flash_size ? info.flash_size : 0x1000000u;
    return DL_OK;
}
