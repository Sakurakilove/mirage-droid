/* devlib_util — error reporting, file helpers, crc32, sha1, rng. */
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "devlib_internal.h"

/* ------------------------------------------------------------------ */
/* Errors                                                              */
/* ------------------------------------------------------------------ */

static char g_error[1024];

const char *dl_strerror(int rc)
{
    switch (rc) {
    case DL_OK: return "ok";
    case DL_ERR_INVALID: return "invalid argument";
    case DL_ERR_IO: return "I/O error";
    case DL_ERR_NOT_FOUND: return "not found";
    case DL_ERR_NOSPACE: return "no space";
    case DL_ERR_FORMAT: return "bad format";
    case DL_ERR_ZLIB: return "decompression failed";
    case DL_ERR_LFS: return "littlefs error";
    default: return "unknown error";
    }
}

const char *dl_last_error(void)
{
    return g_error;
}

void dl_set_error(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, sizeof(g_error), fmt, ap);
    va_end(ap);
}

/* ------------------------------------------------------------------ */
/* Files                                                               */
/* ------------------------------------------------------------------ */

int dl__read_file(const char *path, void **out, size_t *size)
{
    FILE *f = fopen(path, "rb");
    long len;
    void *buf;

    if (!f) {
        dl_set_error("cannot open %s: %s", path, strerror(errno));
        return DL_ERR_IO;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        dl_set_error("cannot seek %s", path);
        return DL_ERR_IO;
    }
    len = ftell(f);
    if (len < 0 || fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        dl_set_error("cannot size %s", path);
        return DL_ERR_IO;
    }
    buf = malloc((size_t)len ? (size_t)len : 1);
    if (!buf) {
        fclose(f);
        dl_set_error("out of memory reading %s", path);
        return DL_ERR_IO;
    }
    if (len > 0 && fread(buf, 1, (size_t)len, f) != (size_t)len) {
        free(buf);
        fclose(f);
        dl_set_error("short read on %s", path);
        return DL_ERR_IO;
    }
    fclose(f);
    *out = buf;
    *size = (size_t)len;
    return DL_OK;
}

int dl__write_file(const char *path, const void *data, size_t size)
{
    FILE *f = fopen(path, "wb");
    if (!f) {
        dl_set_error("cannot create %s: %s", path, strerror(errno));
        return DL_ERR_IO;
    }
    if (size && fwrite(data, 1, size, f) != size) {
        fclose(f);
        dl_set_error("cannot write %s: %s", path, strerror(errno));
        return DL_ERR_IO;
    }
    if (fclose(f) != 0) {
        dl_set_error("cannot close %s: %s", path, strerror(errno));
        return DL_ERR_IO;
    }
    return DL_OK;
}

int64_t dl__file_size(const char *path)
{
    struct stat st;
    if (stat(path, &st) != 0) {
        return -1;
    }
    return (int64_t)st.st_size;
}

int dl__is_dir(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

int dl__mkdirs(const char *path)
{
    char tmp[4096];
    size_t len, i;

    if (!path || !*path) {
        return DL_ERR_INVALID;
    }
    len = strlen(path);
    if (len >= sizeof(tmp)) {
        return DL_ERR_INVALID;
    }
    memcpy(tmp, path, len + 1);
    while (len > 1 && tmp[len - 1] == '/') {
        tmp[--len] = 0;
    }
    for (i = 1; i <= len; i++) {
        if (tmp[i] == '/' || tmp[i] == 0) {
            char saved = tmp[i];
            tmp[i] = 0;
            if (mkdir(tmp, 0775) != 0 && errno != EEXIST) {
                dl_set_error("cannot create %s: %s", tmp, strerror(errno));
                return DL_ERR_IO;
            }
            tmp[i] = saved;
        }
    }
    if (!dl__is_dir(path)) {
        dl_set_error("%s is not a directory", path);
        return DL_ERR_IO;
    }
    return DL_OK;
}

int dl__path_join(char *out, size_t cap, const char *dir, const char *name)
{
    int n;

    if (!dir || !name) {
        return DL_ERR_INVALID;
    }
    n = snprintf(out, cap, "%s/%s", dir, name);
    if (n < 0 || (size_t)n >= cap) {
        dl_set_error("path too long: %s/%s", dir, name);
        return DL_ERR_INVALID;
    }
    return DL_OK;
}

int dl__copy_file(const char *from, const char *to, uint64_t *copied)
{
    FILE *in = fopen(from, "rb");
    FILE *out;
    char buf[1 << 16];
    size_t n;
    uint64_t total = 0;

    if (!in) {
        dl_set_error("cannot open %s: %s", from, strerror(errno));
        return DL_ERR_IO;
    }
    out = fopen(to, "wb");
    if (!out) {
        fclose(in);
        dl_set_error("cannot create %s: %s", to, strerror(errno));
        return DL_ERR_IO;
    }
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            fclose(in);
            fclose(out);
            dl_set_error("cannot write %s: %s", to, strerror(errno));
            return DL_ERR_IO;
        }
        total += n;
    }
    if (ferror(in)) {
        fclose(in);
        fclose(out);
        dl_set_error("read error on %s", from);
        return DL_ERR_IO;
    }
    fclose(in);
    if (fclose(out) != 0) {
        dl_set_error("cannot close %s: %s", to, strerror(errno));
        return DL_ERR_IO;
    }
    if (copied) {
        *copied = total;
    }
    return DL_OK;
}

/* Recursively copies a directory tree (used by OTA import for the
 * package's "system" directory). */
int dl__copy_tree(const char *from_dir, const char *to_dir, uint64_t *copied)
{
    DIR *d = opendir(from_dir);
    struct dirent *ent;
    uint64_t total = 0;
    int rc = DL_OK;

    if (!d) {
        dl_set_error("cannot open %s", from_dir);
        return DL_ERR_IO;
    }
    if (dl__mkdirs(to_dir) != DL_OK) {
        closedir(d);
        return DL_ERR_IO;
    }
    while ((ent = readdir(d)) != NULL) {
        char from[4096], to[4096];
        struct stat st;
        if (!strcmp(ent->d_name, ".") || !strcmp(ent->d_name, "..")) {
            continue;
        }
        if (dl__path_join(from, sizeof(from), from_dir, ent->d_name) != DL_OK ||
            dl__path_join(to, sizeof(to), to_dir, ent->d_name) != DL_OK) {
            rc = DL_ERR_INVALID;
            break;
        }
        if (stat(from, &st) != 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            rc = dl__copy_tree(from, to, &total);
        } else if (S_ISREG(st.st_mode)) {
            uint64_t n = 0;
            rc = dl__copy_file(from, to, &n);
            total += n;
        }
        if (rc != DL_OK) {
            break;
        }
    }
    closedir(d);
    if (copied) {
        *copied += total;
    }
    return rc;
}

/* ------------------------------------------------------------------ */
/* CRC-32 / SHA-1 / RNG                                                */
/* ------------------------------------------------------------------ */

static uint32_t g_crc_table[256];
static int g_crc_ready;


static void crc_init(void)
{
    uint32_t i, c;
    for (i = 0; i < 256; i++) {
        c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        }
        g_crc_table[i] = c;
    }
    g_crc_ready = 1;
}

uint32_t dl__crc32_raw(uint32_t seed, const void *data, size_t size)
{
    const uint8_t *p = data;
    uint32_t c = seed;
    if (!g_crc_ready) {
        crc_init();
    }
    for (size_t i = 0; i < size; i++) {
        c = g_crc_table[(c ^ p[i]) & 0xFF] ^ (c >> 8);
    }
    return c;
}

uint32_t dl_crc32(const void *data, size_t size)
{
    return dl__crc32_raw(0xFFFFFFFFu, data, size) ^ 0xFFFFFFFFu;
}

uint32_t dl_crc32_zero_init(const void *data, size_t size)
{
    /* Reflected CRC-32, initial 0, no final XOR (Band 9/10 raw record). */
    const uint8_t *p = data;
    uint32_t c = 0;
    if (!g_crc_ready) {
        crc_init();
    }
    for (size_t i = 0; i < size; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c & 1) ? (c >> 1) ^ 0xEDB88320u : c >> 1;
        }
    }
    return c;
}

typedef struct {
    uint32_t h[5];
    uint64_t len;
    uint8_t buf[64];
    size_t used;
} sha1_ctx;

static void sha1_block(sha1_ctx *c, const uint8_t *p)
{
    uint32_t w[80], a, b, d, e, f, k, t, cc;
    for (int i = 0; i < 16; i++) {
        w[i] = dl__be32(p + 4 * i);
    }
    for (int i = 16; i < 80; i++) {
        t = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
        w[i] = (t << 1) | (t >> 31);
    }
    a = c->h[0];
    b = c->h[1];
    cc = c->h[2];
    d = c->h[3];
    e = c->h[4];
    for (int i = 0; i < 80; i++) {
        if (i < 20) {
            f = (b & cc) | (~b & d);
            k = 0x5A827999u;
        } else if (i < 40) {
            f = b ^ cc ^ d;
            k = 0x6ED9EBA1u;
        } else if (i < 60) {
            f = (b & cc) | (b & d) | (cc & d);
            k = 0x8F1BBCDCu;
        } else {
            f = b ^ cc ^ d;
            k = 0xCA62C1D6u;
        }
        t = ((a << 5) | (a >> 27)) + f + e + k + w[i];
        e = d;
        d = cc;
        cc = (b << 30) | (b >> 2);
        b = a;
        a = t;
    }
    c->h[0] += a;
    c->h[1] += b;
    c->h[2] += cc;
    c->h[3] += d;
    c->h[4] += e;
}

void dl__sha1(const void *data, size_t size, uint8_t out[20])
{
    sha1_ctx c;
    const uint8_t *p = data;
    uint64_t bits = (uint64_t)size * 8;
    uint8_t tail[64];

    c.h[0] = 0x67452301u;
    c.h[1] = 0xEFCDAB89u;
    c.h[2] = 0x98BADCFEu;
    c.h[3] = 0x10325476u;
    c.h[4] = 0xC3D2E1F0u;
    c.len = 0;
    c.used = 0;

    while (size >= 64) {
        sha1_block(&c, p);
        p += 64;
        size -= 64;
        c.len += 64;
    }
    memset(c.buf, 0, sizeof(c.buf));
    memcpy(c.buf, p, size);
    c.buf[size] = 0x80;
    if (size >= 56) {
        sha1_block(&c, c.buf);
        memset(c.buf, 0, sizeof(c.buf));
    }
    memcpy(tail, c.buf, 56);
    for (int i = 0; i < 8; i++) {
        tail[56 + i] = (uint8_t)(bits >> (56 - 8 * i));
    }
    sha1_block(&c, tail);
    for (int i = 0; i < 5; i++) {
        out[4 * i + 0] = (uint8_t)(c.h[i] >> 24);
        out[4 * i + 1] = (uint8_t)(c.h[i] >> 16);
        out[4 * i + 2] = (uint8_t)(c.h[i] >> 8);
        out[4 * i + 3] = (uint8_t)c.h[i];
    }
}

static uint32_t g_rng_state = 0x1502C0DEu;

void dl_srand(uint32_t seed)
{
    g_rng_state = seed ? seed : 0x1502C0DEu;
}

uint32_t dl__rand_u32(void)
{
    uint32_t x = g_rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    if (!x) {
        x = 0x9E3779B9u;
    }
    g_rng_state = x;
    return x;
}

uint32_t dl_rand(void)
{
    return dl__rand_u32();
}
