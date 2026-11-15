/*
 * devlib_lfs — littlefs (BSD-3-Clause) wrapped for MirageDroid's in-memory
 * image building. Mirrors Mirage's CLittleFS/mirage_lfs.c: build a
 * filesystem in a RAM buffer, read a file back, list the root directory.
 *
 * Compile lfs.c and lfs_util.c with -DLFS_MULTIVERSION (the on-disk version
 * is pinned per profile: 0x00020000 = littlefs 2.0, 0x00020001 = 2.1).
 */
#ifndef DEVLIB_LFS_H
#define DEVLIB_LFS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dl_lfs_geo {
    uint32_t block_size;
    uint32_t block_count;
    uint32_t read_size;
    uint32_t prog_size;
    uint32_t name_max;
    uint32_t disk_version;   /* 0x00020000 / 0x00020001; 0 = library default */
} dl_lfs_geo;

typedef struct dl_lfs_file {
    const char *path;
    const void *data;
    size_t size;
} dl_lfs_file;

/* Formats `image` (geo.block_size * geo.block_count bytes) and writes
 * `files` into it. Returns 0 or a negative littlefs error. */
int dl_lfs_build(void *image, const dl_lfs_geo *geo,
                 const dl_lfs_file *files, size_t nfiles);

/* Reads `path`; returns the file size and copies at most `cap` bytes into
 * `out`, or a negative littlefs error (LFS_ERR_CORRUPT when the image holds
 * no filesystem). */
long dl_lfs_read(const void *image, const dl_lfs_geo *geo,
                 const char *path, void *out, size_t cap);

/* Replaces (or creates) `path` in an existing image. */
int dl_lfs_write(void *image, const dl_lfs_geo *geo,
                 const char *path, const void *data, size_t size);

/* Names in the root directory, '\n'-separated into `out` (NUL terminated).
 * Returns the entry count or a negative error. */
int dl_lfs_list_root(const void *image, const dl_lfs_geo *geo,
                     char *out, size_t cap);

#ifdef __cplusplus
}
#endif

#endif /* DEVLIB_LFS_H */
