/* Internal yaffs2 writer API (devlib_yaffs2.c). */
#ifndef DEVLIB_YAFFS2_H
#define DEVLIB_YAFFS2_H

#include <stddef.h>
#include <stdint.h>

#include "devlib.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    char name[256];
    char path[4096];   /* source file (unused for directories) */
    uint64_t size;
    int is_dir;
    uint32_t oid;
    uint32_t parent;
} dl_yaffs2_file;

/* Emits every 2048-byte page (the inband tag is already in the last 16
 * bytes). Returns 0 or a negative devlib error. */
typedef int (*dl_yaffs2_emit_fn)(void *ctx, const uint8_t page[2048]);
int dl_yaffs2_emit(const dl_yaffs2_file *files, int count, dl_yaffs2_emit_fn emit,
                   void *ctx);
int dl_yaffs2_page_count(const dl_yaffs2_file *files, int count);

/* Builds the /system tree: a "system" directory in the package if present,
 * else the package's main_res files from ota.json, else the flat files. */
int dl_yaffs2_system_files(const char *firmware_dir, dl_yaffs2_file **out, int *count);
void dl_yaffs2_files_free(dl_yaffs2_file *files);

/* Bind databases: the pages to write at the data partition start. */
int dl_seed_bind_build(const char *config_dir, const char *bt_database,
                       const dl_bind *bind, const char *unused,
                       uint8_t (**pages)[2048], size_t *page_count);
void dl_seed_bind_free(uint8_t (*pages)[2048]);

#ifdef __cplusplus
}
#endif

#endif /* DEVLIB_YAFFS2_H */
