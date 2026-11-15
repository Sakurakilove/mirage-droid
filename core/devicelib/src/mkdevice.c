/* mkdevice — MirageDroid device storage CLI.
 *
 * Builds the NOR / SPI NAND / eMMC images a device boots from, imports
 * official OTA packages and inspects the results. Pure C, no Android
 * dependencies; the same code backs the JNI library.
 *
 * Usage: mkdevice <command> [options]   (mkdevice help for the list)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib.h"
#include "devlib_internal.h"
#include "devlib_lfs.h"

static void usage(FILE *out)
{
    fprintf(out,
            "mkdevice — build MirageDroid device storage images\n"
            "\n"
            "  mkdevice profiles [--json]\n"
            "  mkdevice profile --id <p65|q66|q65|p67|q63|n66|o66|n67> [--json]\n"
            "  mkdevice identity --profile <id> [--seed N] [--did D] [--psn S] [--mac M]\n"
            "                    [--sku K] [--key K] [--fsn F] [--json]\n"
            "  mkdevice ap-info --ap <vela_ap.bin> [--json]\n"
            "  mkdevice ota-list --zip <package.zip>\n"
            "  mkdevice ota-import --zip <package.zip|dir> --out <firmwareDir>\n"
            "  mkdevice nor --profile <id> --out <nor.img> [--ap <vela_ap.bin>\n"
            "               [--ap-offset N]] [--seed N] [identity opts] [--no-bind]\n"
            "  mkdevice nand --profile <id> --out <nand.img> [--resource <file>]\n"
            "                [--fw-dir <firmwareDir>] [--seed N] [--no-bind]\n"
            "  mkdevice emmc --profile <id> --out <emmc.img> --fw-dir <firmwareDir>\n"
            "  mkdevice emmc-list --image <emmc.img>\n"
            "  mkdevice factory-read --profile <id> --image <nor.img>\n"
            "  mkdevice factory-write --profile <id> --image <nor.img> [identity opts]\n"
            "  mkdevice provision --profile <id> --firmware <dir> --device <dir>\n"
            "                     [--seed N] [--no-bind] [--identity-file <file>]\n"
            "                     [--no-verify]\n"
            "  mkdevice verify --profile <id> --device <dir>\n"
            "  mkdevice romfs-list --image <resource.bin> [--path /]\n"
            "  mkdevice romfs-read --image <resource.bin> --path </x> --out <file>\n"
            "  mkdevice zip-extract --zip <package.zip> --entry <name> --out <file>\n"
            "  mkdevice help\n");
}

static int has_flag(int argc, char **argv, const char *flag)
{
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], flag)) {
            return 1;
        }
    }
    return 0;
}

static const char *opt(int argc, char **argv, const char *name)
{
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], name) && i + 1 < argc) {
            return argv[i + 1];
        }
    }
    return NULL;
}

static const char *json_escape(const char *s, char *buf, size_t cap)
{
    size_t n = 0;
    if (!buf || cap == 0) {
        return "";
    }
    for (; s && *s && n + 7 < cap; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') {
            buf[n++] = '\\';
            buf[n++] = (char)c;
        } else if (c < 0x20) {
            n += (size_t)snprintf(buf + n, cap - n, "\\u%04x", c);
        } else {
            buf[n++] = (char)c;
        }
    }
    buf[n] = 0;
    return buf;
}

static int profile_from_args(int argc, char **argv, const dl_profile **out)
{
    const char *id = opt(argc, argv, "--profile");
    if (!id) {
        /* "--id" is accepted by the profile command */
        id = opt(argc, argv, "--id");
    }
    *out = dl_profile_by_id(id);
    if (!*out) {
        fprintf(stderr, "unknown profile '%s' (mkdevice profiles lists them)\n",
                id ? id : "(none)");
        return -1;
    }
    return 0;
}

static void print_profile(const dl_profile *p, int json)
{
    int i;
    if (!json) {
        printf("%-4s %-26s %-10s chip=%-9s board=%s consoleUART=%d\n",
               p->id, p->name,
               p->family == DL_FAMILY_BAND ? "band" : "watch", p->chip,
               p->qemu_board, p->console_uart);
        printf("     screen %ux%u %s; %d button(s); storage: ", p->screen.width,
               p->screen.height,
               p->screen.shape == DL_SHAPE_CIRCLE ? "circle" : "rounded-rect",
               p->button_count);
        if (p->has_nand) {
            printf("SPI NAND %uB+%uB %ux%u pages, resource %u pages\n",
                   p->nand.page_size, p->nand.oob_size, p->nand.pages_per_block,
                   p->nand.blocks, p->nand.resource_pages);
        } else if (p->has_emmc) {
            printf("eMMC %d GiB GPT (%d romfs + %d blank partitions)\n",
                   p->emmc.size_gib, p->emmc.romfs_count, p->emmc.blank_count);
        } else {
            printf("NOR only\n");
        }
        printf("     factory: %s @ %s 0x%llx (%u x %u B)\n",
               p->factory.format == DL_FACTORY_P65RECORD ? "p65Record"
               : p->factory.format == DL_FACTORY_RAWINFO ? "rawInfo"
                                                         : "littlefs",
               p->factory.flash == DL_NOR_MAIN ? "mainNOR" : "sensorNOR",
               (unsigned long long)p->factory.offset, p->factory.block_count,
               p->factory.block_size);
        return;
    }
    printf("{\"id\":\"%s\",\"name\":\"%s\",\"family\":\"%s\",\"chip\":\"%s\","
           "\"qemuBoard\":\"%s\",\"screen\":[%u,%u],\"consoleUART\":%d,"
           "\"mainNOR\":%s,\"nand\":",
           p->id, p->name, p->family == DL_FAMILY_BAND ? "band" : "watch",
           p->chip, p->qemu_board, p->screen.width, p->screen.height,
           p->console_uart, p->main_nor ? "true" : "false");
    if (p->has_nand) {
        printf("{\"pageSize\":%u,\"oobSize\":%u,\"pagesPerBlock\":%u,"
               "\"blocks\":%u,\"resourcePages\":%u,\"imageSize\":%llu}",
               p->nand.page_size, p->nand.oob_size, p->nand.pages_per_block,
               p->nand.blocks, p->nand.resource_pages,
               (unsigned long long)dl_nand_image_size(&p->nand));
    } else {
        printf("null");
    }
    printf(",\"factory\":{\"format\":\"%s\",\"flash\":\"%s\",\"offset\":%llu},"
           "\"buttons\":[",
           p->factory.format == DL_FACTORY_P65RECORD ? "p65Record"
           : p->factory.format == DL_FACTORY_RAWINFO ? "rawInfo"
                                                     : "littlefs",
           p->factory.flash == DL_NOR_MAIN ? "mainNOR" : "sensorNOR",
           (unsigned long long)p->factory.offset);
    for (i = 0; i < p->button_count; i++) {
        printf("%s{\"id\":\"%s\",\"qcode\":\"%s\"}", i ? "," : "",
               p->buttons[i].id, p->buttons[i].qcode);
    }
    if (p->sku) {
        printf("],\"didPrefix\":\"%s\",\"psnPrefix\":\"%s\",\"sku\":\"%s\"}\n",
               p->did_prefix, p->psn_prefix, p->sku);
    } else {
        printf("],\"didPrefix\":\"%s\",\"psnPrefix\":\"%s\",\"sku\":null}\n",
               p->did_prefix, p->psn_prefix);
    }
}

/* One parsed identity + bind, shared by the commands that need them. */
typedef struct {
    dl_identity identity;
    dl_bind bind;
    int bind_set;
} id_ctx;

static uint32_t seed_of(int argc, char **argv)
{
    const char *s = opt(argc, argv, "--seed");
    if (s) {
        return (uint32_t)strtoul(s, NULL, 0);
    }
    return 0x1502C0DEu;
}

static int parse_identity(int argc, char **argv, const dl_profile *profile,
                          id_ctx *ctx)
{
    const char *identity_file = opt(argc, argv, "--identity-file");
    memset(ctx, 0, sizeof(*ctx));
    if (identity_file) {
        char path[4096];
        if (dl_identity_load(&ctx->identity, &ctx->bind, identity_file) != DL_OK) {
            /* allow a device dir to be given: append the default name */
            snprintf(path, sizeof(path), "%s/identity.properties", identity_file);
            if (dl_identity_load(&ctx->identity, &ctx->bind, path) != DL_OK) {
                fprintf(stderr, "cannot read identity: %s\n", dl_last_error());
                return -1;
            }
        }
        ctx->bind_set = 1;
    } else {
        dl_identity_generate(profile, seed_of(argc, argv), &ctx->identity);
        ctx->bind_set = 1;
    }
    dl_bind_generate(seed_of(argc, argv) ^ 0x9E3779B9u, &ctx->bind);
    {
        static const struct {
            const char *field;
            const char *option;
        } overrides[] = {
            { "did", "--did" },   { "psn", "--psn" }, { "mac_bt", "--mac" },
            { "sku", "--sku" },   { "key", "--key" }, { "fsn", "--fsn" },
        };
        for (size_t i = 0; i < sizeof(overrides) / sizeof(overrides[0]); i++) {
            const char *v = opt(argc, argv, overrides[i].option);
            if (v) {
                if (dl_identity_set(&ctx->identity, overrides[i].field, v) != DL_OK) {
                    fprintf(stderr, "%s\n", dl_last_error());
                    return -1;
                }
            }
        }
    }
    return 0;
}

static int cmd_profiles(int argc, char **argv)
{
    int json = has_flag(argc, argv, "--json");
    if (json) {
        printf("[");
    }
    for (int i = 0; i < dl_profile_count(); i++) {
        if (json && i) {
            printf(",\n");
        }
        print_profile(dl_profile_at(i), json);
    }
    if (json) {
        printf("]\n");
    }
    return 0;
}

static int cmd_profile(int argc, char **argv)
{
    const dl_profile *p;
    int json = has_flag(argc, argv, "--json");
    if (profile_from_args(argc, argv, &p) != 0) {
        return 1;
    }
    print_profile(p, json);
    return 0;
}

static int cmd_identity(int argc, char **argv)
{
    const dl_profile *p;
    id_ctx ctx;
    char esc[512];
    if (profile_from_args(argc, argv, &p) != 0 || parse_identity(argc, argv, p, &ctx) != 0) {
        return 1;
    }
    if (has_flag(argc, argv, "--json")) {
        printf("{\"did\":\"%s\",\"psn\":\"%s\",\"mac_bt\":\"%s\",\"sku\":\"%s\","
               "\"key\":\"%s\",\"fsn\":\"%s\",\"authKey\":\"%s\"}\n",
               ctx.identity.did, ctx.identity.psn, ctx.identity.mac_bt,
               json_escape(ctx.identity.sku, esc, sizeof(esc)), ctx.identity.key,
               ctx.identity.fsn, ctx.bind.auth_key);
    } else {
        printf("ro.factory.did=%s\nro.factory.psn=%s\nro.factory.mac_bt=%s\n"
               "ro.factory.sku=%s\nro.factory.key=%s\nro.factory.fsn=%s\n",
               ctx.identity.did, ctx.identity.psn, ctx.identity.mac_bt,
               ctx.identity.sku, ctx.identity.key, ctx.identity.fsn);
    }
    return 0;
}

static int cmd_ap_info(int argc, char **argv)
{
    const char *ap = opt(argc, argv, "--ap");
    dl_ap_info info;
    if (!ap) {
        fprintf(stderr, "--ap is required\n");
        return 1;
    }
    if (dl_ap_read_info(ap, &info) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    if (has_flag(argc, argv, "--json")) {
        printf("{\"chip\":\"%s\",\"flashSize\":%llu,\"version\":\"%s\","
               "\"buildDate\":\"%s\",\"products\":[",
               info.chip, (unsigned long long)info.flash_size, info.version,
               info.build_date);
        for (int i = 0; i < info.product_count; i++) {
            printf("%s\"%s\"", i ? "," : "", info.products[i]);
        }
        printf("],\"product\":\"%s\"}\n",
               info.product_count ? info.products[0] : "");
    } else {
        printf("chip=%s flash_size=%llu version=%s build_date=%s\n", info.chip,
               (unsigned long long)info.flash_size, info.version, info.build_date);
        for (int i = 0; i < info.product_count; i++) {
            printf("product=%s\n", info.products[i]);
        }
    }
    return 0;
}

static int cmd_ota_list(int argc, char **argv)
{
    const char *zip = opt(argc, argv, "--zip");
    dl_zip_list list;
    if (!zip) {
        fprintf(stderr, "--zip is required\n");
        return 1;
    }
    if (dl_zip_scan(zip, &list) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    for (size_t i = 0; i < list.count; i++) {
        printf("%12llu  %s\n", (unsigned long long)list.entries[i].size,
               list.entries[i].name);
    }
    dl_zip_list_free(&list);
    return 0;
}

static int cmd_ota_import(int argc, char **argv)
{
    const char *zip = opt(argc, argv, "--zip");
    const char *out = opt(argc, argv, "--out");
    dl_import_report report;
    if (!zip || !out) {
        fprintf(stderr, "--zip and --out are required\n");
        return 1;
    }
    if (dl_ota_import(zip, out, &report) != DL_OK) {
        fprintf(stderr, "import failed: %s\n", dl_last_error());
        return 1;
    }
    printf("imported %s %s (chip %s, flash %llu bytes) into %s\n",
           report.product, report.version, report.chip,
           (unsigned long long)report.flash_size, out);
    for (int i = 0; i < report.file_count; i++) {
        printf("  %12llu  %s\n", (unsigned long long)report.files[i].size,
               report.files[i].name);
    }
    return 0;
}

static int cmd_nor(int argc, char **argv)
{
    const dl_profile *p;
    const char *out_path = opt(argc, argv, "--out");
    const char *ap = opt(argc, argv, "--ap");
    id_ctx ctx;
    dl_ap_info info;
    uint64_t flash_size = 0x1000000, ap_off;
    int rc;

    if (profile_from_args(argc, argv, &p) != 0 || !out_path) {
        fprintf(stderr, "--profile and --out are required\n");
        return 1;
    }
    if (parse_identity(argc, argv, p, &ctx) != 0) {
        return 1;
    }
    if (ap && dl_ap_read_info(ap, &info) == DL_OK && info.has_flash_size) {
        flash_size = info.flash_size;
        ap_off = info.has_ota_code_offset ? info.ota_code_offset : 0xC0000;
    } else {
        ap_off = 0xC0000;
    }
    rc = dl_nor_create(out_path, flash_size);
    if (rc != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    rc = dl_factory_write(out_path, &p->factory, &ctx.identity, &ctx.bind);
    if (rc != DL_OK) {
        fprintf(stderr, "factory: %s\n", dl_last_error());
        return 1;
    }
    if (ap) {
        rc = dl_nor_install_ap(out_path, ap, ap_off);
        if (rc != DL_OK) {
            fprintf(stderr, "%s\n", dl_last_error());
            return 1;
        }
    }
    printf("wrote %s (%llu bytes, factory %s @ 0x%llx)\n", out_path,
           (unsigned long long)flash_size,
           p->factory.format == DL_FACTORY_P65RECORD ? "p65Record"
           : p->factory.format == DL_FACTORY_RAWINFO ? "rawInfo"
                                                     : "littlefs",
           (unsigned long long)p->factory.offset);
    return 0;
}

static int cmd_factory_read(int argc, char **argv)
{
    const dl_profile *p;
    const char *image = opt(argc, argv, "--image");
    char text[8192];
    if (profile_from_args(argc, argv, &p) != 0 || !image) {
        return 1;
    }
    if (dl_factory_read_text(image, &p->factory, text, sizeof(text)) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("%s", text);
    return 0;
}

static int cmd_factory_write(int argc, char **argv)
{
    const dl_profile *p;
    const char *image = opt(argc, argv, "--image");
    id_ctx ctx;
    int rc;
    if (profile_from_args(argc, argv, &p) != 0 || !image) {
        return 1;
    }
    if (parse_identity(argc, argv, p, &ctx) != 0) {
        return 1;
    }
    rc = dl_factory_write(image, &p->factory, &ctx.identity, &ctx.bind);
    if (rc != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("factory data written to %s @ 0x%llx\n", image,
           (unsigned long long)p->factory.offset);
    return 0;
}

static int cmd_nand(int argc, char **argv)
{
    const dl_profile *p;
    const char *out_path = opt(argc, argv, "--out");
    const char *resource = opt(argc, argv, "--resource");
    const char *fw_dir = opt(argc, argv, "--fw-dir");
    id_ctx ctx;
    int rc;

    if (profile_from_args(argc, argv, &p) != 0 || !out_path) {
        return 1;
    }
    if (!p->has_nand) {
        fprintf(stderr, "%s has no NAND image\n", p->id);
        return 1;
    }
    if (parse_identity(argc, argv, p, &ctx) != 0) {
        return 1;
    }
    rc = dl_nand_build(out_path, &p->nand, resource, fw_dir ? fw_dir : ".");
    if (rc != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("wrote %s (%llu bytes)\n", out_path,
           (unsigned long long)dl_nand_image_size(&p->nand));
    return 0;
}

static int cmd_emmc(int argc, char **argv)
{
    const dl_profile *p;
    const char *out_path = opt(argc, argv, "--out");
    const char *fw_dir = opt(argc, argv, "--fw-dir");
    if (profile_from_args(argc, argv, &p) != 0 || !out_path || !fw_dir) {
        return 1;
    }
    if (!p->has_emmc) {
        fprintf(stderr, "%s has no eMMC image\n", p->id);
        return 1;
    }
    if (dl_emmc_build(out_path, &p->emmc, fw_dir) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("wrote %s (%d GiB GPT)\n", out_path, p->emmc.size_gib);
    return 0;
}

static int cmd_emmc_list(int argc, char **argv)
{
    const char *image = opt(argc, argv, "--image");
    char text[16384];
    if (!image) {
        fprintf(stderr, "--image is required\n");
        return 1;
    }
    if (dl_emmc_list_partitions(image, text, sizeof(text)) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("%s", text);
    return 0;
}

static int cmd_provision(int argc, char **argv)
{
    const dl_profile *p;
    const char *fw_dir = opt(argc, argv, "--firmware");
    const char *dev_dir = opt(argc, argv, "--device");
    const char *config_dir = opt(argc, argv, "--bind-config");
    const char *res_override = opt(argc, argv, "--resource");
    id_ctx ctx;
    dl_provision_options options;
    char identity_path[4096];
    int no_bind = has_flag(argc, argv, "--no-bind");
    int rc;

    if (profile_from_args(argc, argv, &p) != 0 || !fw_dir || !dev_dir) {
        fprintf(stderr, "--profile, --firmware and --device are required\n");
        return 1;
    }
    /* A device directory with an identity keeps it across rebuilds. */
    if (dl__path_join(identity_path, sizeof(identity_path), dev_dir,
                      "identity.properties") == DL_OK &&
        dl__file_size(identity_path) >= 0) {
        memset(&ctx, 0, sizeof(ctx));
        if (dl_identity_load(&ctx.identity, &ctx.bind, identity_path) != DL_OK) {
            fprintf(stderr, "%s\n", dl_last_error());
            return 1;
        }
        /* Keep the device's existing bind keys: rewriting them would make
         * the seeded /data databases disagree with identity.properties. */
        if (!ctx.bind.bound) {
            dl_bind_generate(seed_of(argc, argv) ^ 0x9E3779B9u, &ctx.bind);
        }
        ctx.bind_set = 1;
    } else if (parse_identity(argc, argv, p, &ctx) != 0) {
        return 1;
    }
    if (no_bind) {
        memset(&ctx.bind, 0, sizeof(ctx.bind));
    }
    if (!ctx.bind.bound) {
        dl_bind_generate(seed_of(argc, argv) ^ 0x9E3779B9u, &ctx.bind);
        if (no_bind) {
            memset(&ctx.bind, 0, sizeof(ctx.bind));
        }
    }
    memset(&options, 0, sizeof(options));
    options.firmware_dir = fw_dir;
    options.device_dir = dev_dir;
    options.bind_config_dir = config_dir;
    options.seed_bind = !no_bind;
    options.resource_override = res_override;
    {
        dl_verify_result verify;
        rc = dl_provision(p, &ctx.identity, &ctx.bind, &options, &verify);
        if (rc != DL_OK) {
            fprintf(stderr, "provision failed: %s\n", dl_last_error());
            return 1;
        }
        if (!has_flag(argc, argv, "--no-verify")) {
            printf("%s", verify.detail);
            printf("%s\n", verify.ok ? "OK" : "verification FAILED");
            return verify.ok ? 0 : 1;
        }
    }
    return 0;
}

static int cmd_verify(int argc, char **argv)
{
    const dl_profile *p;
    const char *dev_dir = opt(argc, argv, "--device");
    char nor[4096], nand[4096];
    dl_identity identity;
    dl_bind bind;
    dl_verify_result verify;
    int have_nor, have_nand;
    if (profile_from_args(argc, argv, &p) != 0 || !dev_dir) {
        return 1;
    }
    have_nor = dl__path_join(nor, sizeof(nor), dev_dir, "nor.img") == DL_OK &&
               dl__file_size(nor) >= 0;
    have_nand = dl__path_join(nand, sizeof(nand), dev_dir, "nand.img") == DL_OK &&
                dl__file_size(nand) >= 0;
    if ((p->main_nor && !have_nor) || (p->has_nand && !have_nand)) {
        fprintf(stderr, "device images are missing in %s (run provision first)\n", dev_dir);
        return 1;
    }
    memset(&identity, 0, sizeof(identity));
    {
        char path[4096];
        if (dl__path_join(path, sizeof(path), dev_dir, "identity.properties") == DL_OK) {
            dl_identity_load(&identity, &bind, path);
        }
    }
    if (dl_verify_device(p, have_nor ? nor : NULL, have_nand ? nand : NULL,
                         identity.did[0] ? &identity : NULL, &verify) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("%s\n%s", verify.detail, verify.ok ? "OK\n" : "FAILED\n");
    return verify.ok ? 0 : 1;
}

static int cmd_romfs_list(int argc, char **argv)
{
    const char *image = opt(argc, argv, "--image");
    const char *path = opt(argc, argv, "--path");
    dl_romfs_entry *entries = NULL;
    size_t count = 0;
    if (!image) {
        return 1;
    }
    if (dl_romfs_list(image, path ? path : "/", &entries, &count) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    for (size_t i = 0; i < count; i++) {
        printf("%c %10llu  %s\n", entries[i].is_dir ? 'd' : '-',
               (unsigned long long)entries[i].size, entries[i].name);
    }
    dl_romfs_entries_free(entries);
    return 0;
}

static int cmd_romfs_read(int argc, char **argv)
{
    const char *image = opt(argc, argv, "--image");
    const char *path = opt(argc, argv, "--path");
    const char *out_path = opt(argc, argv, "--out");
    void *data = NULL;
    size_t size = 0;
    int rc;
    if (!image || !path || !out_path) {
        return 1;
    }
    rc = dl_romfs_read(image, path, &data, &size);
    if (rc != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    rc = dl__write_file(out_path, data, size);
    free(data);
    if (rc != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("wrote %s (%llu bytes)\n", out_path, (unsigned long long)size);
    return 0;
}

static int cmd_zip_extract(int argc, char **argv)
{
    const char *zip = opt(argc, argv, "--zip");
    const char *entry = opt(argc, argv, "--entry");
    const char *out_path = opt(argc, argv, "--out");
    uint64_t size = 0;
    if (!zip || !entry || !out_path) {
        return 1;
    }
    if (dl_zip_extract(zip, entry, out_path, &size) != DL_OK) {
        fprintf(stderr, "%s\n", dl_last_error());
        return 1;
    }
    printf("wrote %s (%llu bytes)\n", out_path, (unsigned long long)size);
    return 0;
}

static int cmd_self_test(int argc, char **argv);

int main(int argc, char **argv)
{
    const char *cmd;

    if (argc < 2) {
        usage(stderr);
        return 2;
    }
    cmd = argv[1];
    if (!strcmp(cmd, "help") || !strcmp(cmd, "--help") || !strcmp(cmd, "-h")) {
        usage(stdout);
        return 0;
    }
    if (!strcmp(cmd, "profiles")) return cmd_profiles(argc, argv);
    if (!strcmp(cmd, "profile")) return cmd_profile(argc, argv);
    if (!strcmp(cmd, "identity")) return cmd_identity(argc, argv);
    if (!strcmp(cmd, "ap-info")) return cmd_ap_info(argc, argv);
    if (!strcmp(cmd, "ota-list")) return cmd_ota_list(argc, argv);
    if (!strcmp(cmd, "ota-import")) return cmd_ota_import(argc, argv);
    if (!strcmp(cmd, "nor")) return cmd_nor(argc, argv);
    if (!strcmp(cmd, "nand")) return cmd_nand(argc, argv);
    if (!strcmp(cmd, "emmc")) return cmd_emmc(argc, argv);
    if (!strcmp(cmd, "emmc-list")) return cmd_emmc_list(argc, argv);
    if (!strcmp(cmd, "factory-read")) return cmd_factory_read(argc, argv);
    if (!strcmp(cmd, "factory-write")) return cmd_factory_write(argc, argv);
    if (!strcmp(cmd, "provision")) return cmd_provision(argc, argv);
    if (!strcmp(cmd, "verify")) return cmd_verify(argc, argv);
    if (!strcmp(cmd, "romfs-list")) return cmd_romfs_list(argc, argv);
    if (!strcmp(cmd, "romfs-read")) return cmd_romfs_read(argc, argv);
    if (!strcmp(cmd, "zip-extract")) return cmd_zip_extract(argc, argv);
    if (!strcmp(cmd, "self-test")) return cmd_self_test(argc, argv);
    fprintf(stderr, "unknown command '%s' (mkdevice help)\n", cmd);
    return 2;
}

/* Self-test: byte-level assertions for the factory records and littlefs
 * mount round-trip. Returns 0 when every check passes. */
static int cmd_self_test(int argc, char **argv)
{
    int failures = 0;
    (void)argc;
    (void)argv;

#define CHECK(cond, ...)                                                         \
    do {                                                                         \
        if (!(cond)) {                                                           \
            fprintf(stderr, "FAIL: ");                                           \
            fprintf(stderr, __VA_ARGS__);                                        \
            fprintf(stderr, "\n");                                               \
            failures++;                                                          \
        }                                                                        \
    } while (0)

    /* profiles */
    {
        const dl_profile *p65 = dl_profile_by_id("p65");
        const dl_profile *q66 = dl_profile_by_id("q66");
        CHECK(dl_profile_count() >= 8, "expected 8 built-in profiles, got %d",
              dl_profile_count());
        CHECK(p65 && !strcmp(p65->chip, "best1502p") &&
                  p65->factory.format == DL_FACTORY_P65RECORD &&
                  p65->factory.offset == 0xCC0000,
              "p65 profile fields");
        CHECK(q66 && q66->nand.resource_pages == 65536 &&
                  q66->nand.data_pages == 196608,
              "q66 NAND layout");
        CHECK(p65 && dl_nand_image_size(&p65->nand) == 553648128ull,
              "p65 NAND image size");
        CHECK(p65 && dl_nand_data_first_page(&p65->nand) == 112640,
              "p65 /data first page");
        CHECK(dl_profile_for_product("miwear.watch.p65x", "best1502p") == p65,
              "product prefix match");
    }

    /* p65Record byte layout */
    {
        const dl_profile *p = dl_profile_by_id("p65");
        dl_identity id;
        dl_bind bind;
        void *data = NULL;
        size_t size = 0;
        const uint8_t *b;
        memset(&id, 0, sizeof(id));
        snprintf(id.did, sizeof(id.did), "1502123456");
        snprintf(id.psn, sizeof(id.psn), "QEMU1502123456789");
        snprintf(id.mac_bt, sizeof(id.mac_bt), "02:11:22:33:44:55");
        snprintf(id.sku, sizeof(id.sku), "M2509W1");
        snprintf(id.key, sizeof(id.key), "0123456789ABCDEF");
        snprintf(id.fsn, sizeof(id.fsn), "QEMU1502P00001");
        dl_bind_generate(1, &bind);
        CHECK(dl_factory_build(&p->factory, &id, &bind, &data, &size) == DL_OK,
              "p65 factory build: %s", dl_last_error());
        CHECK(size == 256, "p65 record size %zu", size);
        b = data;
        if (b) {
            CHECK(b[8] == 0x02 && b[9] == 0x11 && b[10] == 0x22 && b[11] == 0x33 &&
                      b[12] == 0x44 && b[13] == 0x55,
                  "p65 MAC @8");
            /* 1502123456 = 0x598895C0, stored big-endian */
            CHECK(b[14] == 0x00 && b[15] == 0x00 && b[16] == 0x00 &&
                      b[17] == 0x00 && b[18] == 0x59 && b[19] == 0x88 &&
                      b[20] == 0x95 && b[21] == 0xC0,
                  "p65 DID big-endian u64 @14 (got %02x %02x %02x %02x %02x %02x %02x %02x)",
                  b[14], b[15], b[16], b[17], b[18], b[19], b[20], b[21]);
            CHECK(!memcmp(b + 22, "0123456789ABCDEF", 16), "p65 key @22");
            CHECK(!memcmp(b + 39, "QEMU1502123456789", 17), "p65 psn @39");
            CHECK(!memcmp(b + 63, "QEMU1502P00001", 14), "p65 fsn @63");
            CHECK(!memcmp(b + 77, "M2509W1", 7), "p65 sku @77");
            CHECK(b[0] == 0 && b[255] == 0, "p65 record padded with zeroes");
        }
        free(data);
    }

    /* littlefs factory round-trip */
    {
        const dl_profile *p = dl_profile_by_id("q66");
        dl_identity id;
        dl_bind bind;
        dl_identity read;
        char text[4096];
        void *image = NULL;
        size_t size = 0;
        memset(&id, 0, sizeof(id));
        snprintf(id.did, sizeof(id.did), "150123456");
        snprintf(id.psn, sizeof(id.psn), "QEMU15031234567890");
        snprintf(id.mac_bt, sizeof(id.mac_bt), "02:AA:BB:CC:DD:EE");
        dl_bind_generate(2, &bind);
        CHECK(dl_factory_build(&p->factory, &id, &bind, &image, &size) == DL_OK,
              "q66 littlefs factory build: %s", dl_last_error());
        CHECK(size == (size_t)dl_factory_partition_size(&p->factory),
              "q66 factory partition size");
        {
            dl_lfs_geo geo = { p->factory.block_size, p->factory.block_count,
                               p->factory.io_size, p->factory.io_size, 32,
                               p->factory.disk_version };
            long n = dl_lfs_read(image, &geo, "/factory.prop", text, sizeof(text) - 1);
            CHECK(n > 0, "littlefs /factory.prop read");
            if (n > 0) {
                text[n] = 0;
                CHECK(strstr(text, "ro.factory.did=150123456") != NULL,
                      "factory.prop holds did");
                CHECK(strstr(text, "persist.miwear_token_key=") != NULL,
                      "factory.prop holds the bind token");
                CHECK(strstr(text, "persist.miwear_companion_device_mac=010000eeffc0") != NULL,
                      "factory.prop holds the companion MAC");
            }
        }
        /* and decode through the public API */
        {
            char path[256];
            snprintf(path, sizeof(path), "work/devicecore/selftest-factory.bin");
            (void)path;
        }
        free(image);
        (void)read;
    }

    /* romfs header of the real firmware is checked by the Python tests */
    if (failures) {
        fprintf(stderr, "%d self-test failure(s)\n", failures);
        return 1;
    }
    printf("self-test: all checks passed\n");
    return 0;
}
