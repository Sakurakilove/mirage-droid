/* devlib_zip — reading official OTA .zip packages (vendored miniz). */
#include <stdlib.h>
#include <string.h>

#include "miniz.h"

#include "devlib_internal.h"

int dl_zip_scan(const char *zip_path, dl_zip_list *out)
{
    mz_zip_archive zip;
    mz_uint n, i;
    dl_zip_entry *entries;

    if (!zip_path || !out) {
        return DL_ERR_INVALID;
    }
    memset(out, 0, sizeof(*out));
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_file(&zip, zip_path, 0)) {
        dl_set_error("%s is not a ZIP firmware package", zip_path);
        return DL_ERR_FORMAT;
    }
    n = mz_zip_reader_get_num_files(&zip);
    entries = calloc(n ? n : 1, sizeof(*entries));
    if (!entries) {
        mz_zip_reader_end(&zip);
        dl_set_error("out of memory listing %s", zip_path);
        return DL_ERR_IO;
    }
    out->count = 0;
    for (i = 0; i < n; i++) {
        mz_zip_archive_file_stat st;
        if (!mz_zip_reader_file_stat(&zip, i, &st)) {
            continue;
        }
        if (st.m_is_directory) {
            continue;
        }
        snprintf(entries[out->count].name, sizeof(entries[out->count].name), "%s",
                 st.m_filename);
        entries[out->count].size = (uint64_t)st.m_uncomp_size;
        out->count++;
    }
    out->entries = entries;
    mz_zip_reader_end(&zip);
    return DL_OK;
}

void dl_zip_list_free(dl_zip_list *list)
{
    if (list) {
        free(list->entries);
        list->entries = NULL;
        list->count = 0;
    }
}

static int find_index(mz_zip_archive *zip, const char *name)
{
    mz_uint i = mz_zip_reader_locate_file(zip, name, NULL, 0);
    if (i == MZ_UINT32_MAX) {
        return -1;
    }
    return (int)i;
}

int dl_zip_extract(const char *zip_path, const char *entry_name, const char *out_path,
                   uint64_t *out_size)
{
    mz_zip_archive zip;
    int idx;
    int rc = DL_OK;

    if (!zip_path || !entry_name || !out_path) {
        return DL_ERR_INVALID;
    }
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_file(&zip, zip_path, 0)) {
        dl_set_error("%s is not a ZIP firmware package", zip_path);
        return DL_ERR_FORMAT;
    }
    idx = find_index(&zip, entry_name);
    if (idx < 0) {
        mz_zip_reader_end(&zip);
        dl_set_error("%s has no entry %s", zip_path, entry_name);
        return DL_ERR_NOT_FOUND;
    }
    {
        mz_zip_archive_file_stat st;
        if (!mz_zip_reader_file_stat(&zip, (mz_uint)idx, &st) ||
            !mz_zip_reader_extract_to_file(&zip, (mz_uint)idx, out_path, 0)) {
            dl_set_error("cannot extract %s from %s", entry_name, zip_path);
            rc = DL_ERR_ZLIB;
        } else if (out_size) {
            *out_size = (uint64_t)st.m_uncomp_size;
        }
    }
    mz_zip_reader_end(&zip);
    return rc;
}

int dl_zip_extract_to_mem(const char *zip_path, const char *entry_name,
                          void **out, size_t *out_size)
{
    mz_zip_archive zip;
    int idx;
    size_t size = 0;
    void *data;

    if (!zip_path || !entry_name || !out) {
        return DL_ERR_INVALID;
    }
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_file(&zip, zip_path, 0)) {
        dl_set_error("%s is not a ZIP firmware package", zip_path);
        return DL_ERR_FORMAT;
    }
    idx = find_index(&zip, entry_name);
    if (idx < 0) {
        mz_zip_reader_end(&zip);
        dl_set_error("%s has no entry %s", zip_path, entry_name);
        return DL_ERR_NOT_FOUND;
    }
    data = mz_zip_reader_extract_to_heap(&zip, (mz_uint)idx, &size, 0);
    mz_zip_reader_end(&zip);
    if (!data) {
        dl_set_error("cannot extract %s (deflate failed)", entry_name);
        return DL_ERR_ZLIB;
    }
    *out = data;
    if (out_size) {
        *out_size = size;
    }
    return DL_OK;
}

int dl_zip_entry_size(const char *zip_path, const char *entry_name, uint64_t *out_size)
{
    mz_zip_archive zip;
    int idx;
    int rc = DL_OK;

    if (!zip_path || !entry_name) {
        return DL_ERR_INVALID;
    }
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_file(&zip, zip_path, 0)) {
        return DL_ERR_FORMAT;
    }
    idx = find_index(&zip, entry_name);
    if (idx < 0) {
        rc = DL_ERR_NOT_FOUND;
    } else {
        mz_zip_archive_file_stat st;
        if (!mz_zip_reader_file_stat(&zip, (mz_uint)idx, &st)) {
            rc = DL_ERR_FORMAT;
        } else if (out_size) {
            *out_size = (uint64_t)st.m_uncomp_size;
        }
    }
    mz_zip_reader_end(&zip);
    return rc;
}
