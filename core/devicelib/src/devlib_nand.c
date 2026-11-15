/* devlib_nand — page-major SPI NAND image builder.
 *
 * Layout (Mirage ImageBuilder.buildNAND / buildSystemNAND):
 *   stride = page_size + oob_size; erased bytes are 0xFF.
 *   resource-romfs layouts: the resource image is written page by page at
 *   resource_first_page; everything else is erased and /data is left blank
 *   for the firmware's autoformat.
 *   yaffs2 layouts: the /system tree occupies pages 0..systemPages, the
 *   rest of the image is erased.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"
#include "devlib_yaffs2.h"

typedef struct {
    FILE *f;
    const dl_nand_spec *spec;
    uint32_t page_size;
    uint32_t oob_size;
    uint32_t stride;
} nand_writer;

static int nw_open(nand_writer *w, const char *path, const dl_nand_spec *spec)
{
    memset(w, 0, sizeof(*w));
    w->spec = spec;
    w->page_size = spec->page_size;
    w->oob_size = spec->oob_size;
    w->stride = spec->page_size + spec->oob_size;
    w->f = fopen(path, "wb");
    if (!w->f) {
        dl_set_error("cannot create %s", path);
        return DL_ERR_IO;
    }
    return DL_OK;
}

static int nw_write_erased_block(nand_writer *w)
{
    size_t n = (size_t)w->stride * w->spec->pages_per_block;
    uint8_t *buf = malloc(n);
    int rc = DL_OK;
    if (!buf) {
        return DL_ERR_IO;
    }
    memset(buf, 0xFF, n);
    if (fwrite(buf, 1, n, w->f) != n) {
        dl_set_error("cannot write the NAND image");
        rc = DL_ERR_IO;
    }
    free(buf);
    return rc;
}

static int nw_write_page(nand_writer *w, const uint8_t *data)
{
    uint8_t *page = malloc(w->stride);
    int rc = DL_OK;
    if (!page) {
        return DL_ERR_IO;
    }
    memcpy(page, data, w->page_size);
    memset(page + w->page_size, 0xFF, w->oob_size);
    if (fwrite(page, 1, w->stride, w->f) != w->stride) {
        dl_set_error("cannot write the NAND image");
        rc = DL_ERR_IO;
    }
    free(page);
    return rc;
}

typedef struct {
    nand_writer *w;
} emit_ctx;

static int emit_page(void *opaque, const uint8_t page[2048])
{
    emit_ctx *ctx = opaque;
    return nw_write_page(ctx->w, page);
}

static int build_resource(nand_writer *w, const char *resource_path)
{
    const dl_nand_spec *spec = w->spec;
    FILE *res = NULL;
    uint64_t total_pages = (uint64_t)spec->blocks * spec->pages_per_block;
    uint64_t res_pages = 0;
    uint8_t *page = NULL;
    int rc = DL_OK;

    if (resource_path && *resource_path) {
        int64_t size = dl__file_size(resource_path);
        if (size < 0) {
            dl_set_error("cannot read the resource image %s", resource_path);
            return DL_ERR_NOT_FOUND;
        }
        if ((uint64_t)size > (uint64_t)spec->resource_pages * spec->page_size) {
            dl_set_error("the resource image (%lld bytes) does not fit the %u-page resource partition",
                         (long long)size, (unsigned)spec->resource_pages);
            return DL_ERR_NOSPACE;
        }
        res = fopen(resource_path, "rb");
        if (!res) {
            dl_set_error("cannot open %s", resource_path);
            return DL_ERR_IO;
        }
        res_pages = ((uint64_t)size + spec->page_size - 1) / spec->page_size;
    }
    page = malloc(spec->page_size);
    if (!page) {
        if (res) {
            fclose(res);
        }
        return DL_ERR_IO;
    }

    for (uint32_t blk = 0; blk < spec->blocks && rc == DL_OK; blk++) {
        uint64_t first = (uint64_t)blk * spec->pages_per_block;
        uint64_t last = first + spec->pages_per_block;
        if (last <= spec->resource_first_page ||
            first >= (uint64_t)spec->resource_first_page + res_pages) {
            rc = nw_write_erased_block(w);
            continue;
        }
        for (uint64_t row = first; row < last; row++) {
            uint64_t p = row >= spec->resource_first_page
                             ? row - spec->resource_first_page
                             : UINT64_MAX;
            if (p != UINT64_MAX && res && p < res_pages) {
                size_t n = fread(page, 1, spec->page_size, res);
                if (n < spec->page_size) {
                    memset(page + n, 0xFF, spec->page_size - n);
                }
                rc = nw_write_page(w, page);
            } else {
                memset(page, 0xFF, spec->page_size);
                rc = nw_write_page(w, page);
            }
            if (rc != DL_OK) {
                break;
            }
        }
    }
    (void)total_pages;
    free(page);
    if (res) {
        fclose(res);
    }
    return rc;
}

static int build_system(nand_writer *w, const char *system_dir)
{
    dl_yaffs2_file *files = NULL;
    int count = 0;
    int need, rc;
    emit_ctx ctx;

    rc = dl_yaffs2_system_files(system_dir, &files, &count);
    if (rc != DL_OK) {
        return rc;
    }
    need = dl_yaffs2_page_count(files, count);
    if ((uint32_t)need > w->spec->system_pages) {
        dl_yaffs2_files_free(files);
        dl_set_error("/system needs %d pages, the partition has %u", need,
                     (unsigned)w->spec->system_pages);
        return DL_ERR_NOSPACE;
    }
    ctx.w = w;
    rc = dl_yaffs2_emit(files, count, emit_page, &ctx);
    dl_yaffs2_files_free(files);
    if (rc != DL_OK) {
        return rc;
    }
    for (int p = need; p < (int)w->spec->blocks * (int)w->spec->pages_per_block; p++) {
        uint8_t *erased = malloc(w->page_size);
        if (!erased) {
            return DL_ERR_IO;
        }
        memset(erased, 0xFF, w->page_size);
        rc = nw_write_page(w, erased);
        free(erased);
        if (rc != DL_OK) {
            return rc;
        }
    }
    return DL_OK;
}

int dl_nand_build(const char *path, const dl_nand_spec *spec,
                  const char *resource_path, const char *system_dir)
{
    nand_writer w;
    int rc;

    if (!path || !spec) {
        return DL_ERR_INVALID;
    }
    if (spec->has_system_pages) {
        if (!system_dir) {
            dl_set_error("the /system partition needs the firmware package's files");
            return DL_ERR_INVALID;
        }
    }
    rc = nw_open(&w, path, spec);
    if (rc != DL_OK) {
        return rc;
    }
    if (spec->has_system_pages) {
        rc = build_system(&w, system_dir);
    } else {
        rc = build_resource(&w, resource_path);
    }
    if (fclose(w.f) != 0 && rc == DL_OK) {
        dl_set_error("cannot close %s", path);
        rc = DL_ERR_IO;
    }
    if (rc != DL_OK) {
        remove(path);
        return rc;
    }
    return DL_OK;
}

int dl_nand_update_resource(const char *path, const dl_nand_spec *spec,
                            const char *resource_path)
{
    FILE *f, *res;
    uint8_t *page;
    uint32_t stride;
    int64_t size;
    uint64_t res_pages;
    int rc = DL_OK;

    if (!path || !spec || !resource_path) {
        return DL_ERR_INVALID;
    }
    size = dl__file_size(resource_path);
    if (size < 0) {
        dl_set_error("cannot read %s", resource_path);
        return DL_ERR_NOT_FOUND;
    }
    if ((uint64_t)size > (uint64_t)spec->resource_pages * spec->page_size) {
        dl_set_error("the resource image does not fit the resource partition");
        return DL_ERR_NOSPACE;
    }
    res_pages = ((uint64_t)size + spec->page_size - 1) / spec->page_size;
    if ((uint64_t)dl__file_size(path) < dl_nand_image_size(spec)) {
        dl_set_error("%s is not a full NAND image", path);
        return DL_ERR_FORMAT;
    }
    stride = spec->page_size + spec->oob_size;
    f = fopen(path, "r+b");
    if (!f) {
        return DL_ERR_IO;
    }
    res = fopen(resource_path, "rb");
    if (!res) {
        fclose(f);
        return DL_ERR_IO;
    }
    page = malloc(stride);
    if (!page) {
        fclose(f);
        fclose(res);
        return DL_ERR_IO;
    }
    if (fseek(f, (long)((uint64_t)spec->resource_first_page * stride), SEEK_SET) != 0) {
        rc = DL_ERR_IO;
    }
    for (uint64_t p = 0; p < spec->resource_pages && rc == DL_OK; p++) {
        size_t n = 0;
        if (p < res_pages) {
            n = fread(page, 1, spec->page_size, res);
        }
        memset(page + n, 0xFF, spec->page_size - n);
        memset(page + spec->page_size, 0xFF, spec->oob_size);
        if (fwrite(page, 1, stride, f) != stride) {
            dl_set_error("cannot update the resource partition");
            rc = DL_ERR_IO;
        }
    }
    free(page);
    fclose(res);
    fclose(f);
    return rc;
}
