/* Internal helpers shared by the devlib translation units. Not public API. */
#ifndef DEVLIB_INTERNAL_H
#define DEVLIB_INTERNAL_H

#include <stddef.h>
#include <stdint.h>

#include "devlib.h"

/* ---- byte order ---- */
static inline uint16_t dl__le16(const void *p)
{
    const uint8_t *b = p;
    return (uint16_t)(b[0] | (b[1] << 8));
}
static inline uint32_t dl__le32(const void *p)
{
    const uint8_t *b = p;
    return (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
           ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}
static inline uint64_t dl__le64(const void *p)
{
    const uint8_t *b = p;
    return (uint64_t)dl__le32(b) | ((uint64_t)dl__le32(b + 4) << 32);
}
static inline uint32_t dl__be32(const void *p)
{
    const uint8_t *b = p;
    return ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
           ((uint32_t)b[2] << 8) | (uint32_t)b[3];
}
static inline void dl__put_le16(void *p, uint16_t v)
{
    uint8_t *b = p;
    b[0] = (uint8_t)v;
    b[1] = (uint8_t)(v >> 8);
}
static inline void dl__put_le32(void *p, uint32_t v)
{
    uint8_t *b = p;
    b[0] = (uint8_t)v;
    b[1] = (uint8_t)(v >> 8);
    b[2] = (uint8_t)(v >> 16);
    b[3] = (uint8_t)(v >> 24);
}
static inline void dl__put_le64(void *p, uint64_t v)
{
    dl__put_le32(p, (uint32_t)v);
    dl__put_le32((uint8_t *)p + 4, (uint32_t)(v >> 32));
}
static inline void dl__put_be32(void *p, uint32_t v)
{
    uint8_t *b = p;
    b[0] = (uint8_t)(v >> 24);
    b[1] = (uint8_t)(v >> 16);
    b[2] = (uint8_t)(v >> 8);
    b[3] = (uint8_t)v;
}

/* ---- files ---- */
int dl__read_file(const char *path, void **out, size_t *size);
int dl__write_file(const char *path, const void *data, size_t size);
int64_t dl__file_size(const char *path);
int dl__mkdirs(const char *path);
int dl__copy_file(const char *from, const char *to, uint64_t *copied);
int dl__copy_tree(const char *from_dir, const char *to_dir, uint64_t *copied);
int dl__is_dir(const char *path);
int dl__path_join(char *out, size_t cap, const char *dir, const char *name);

/* ---- hashing / random ---- */
void dl__sha1(const void *data, size_t size, uint8_t out[20]);
uint32_t dl__rand_u32(void);

/* CRC-32 (reflected 0xEDB88320) with pre/post inversion, for GPT. */
uint32_t dl__crc32_raw(uint32_t seed, const void *data, size_t size);

/* Factory property text (defined in devlib_identity.c). */
int dl_factory_text(const dl_identity *identity, const dl_bind *bind,
                    char *out, size_t cap);

/* __attribute__((format(printf,1,2))) */
#if defined(__GNUC__) || defined(__clang__)
#define DL_PRINTF(a, b) __attribute__((format(printf, a, b)))
#else
#define DL_PRINTF(a, b)
#endif

#endif /* DEVLIB_INTERNAL_H */
