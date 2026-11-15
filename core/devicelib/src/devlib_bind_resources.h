#ifndef DEVLIB_BIND_RESOURCES_H
#define DEVLIB_BIND_RESOURCES_H
#include <stddef.h>
/* Embedded Mirage BindTemplate database ("persist.db", "miwear_record.db",
 * "bt_storage.db", "bt_config.db"). Returns NULL for an unknown name. */
const void *dl_builtin_bind_template(const char *name, size_t *size);
#endif
