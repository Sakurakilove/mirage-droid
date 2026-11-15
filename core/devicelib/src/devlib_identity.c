/* devlib_identity — factory identity and bind state.
 *
 * Mirrors Mirage's FactoryIdentity.generate / BindInfo.generate so a freshly
 * provisioned device looks like one that left the factory line and is
 * already bound to a phone.
 */
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib_internal.h"

/* SplitMix-ish deterministic RNG helpers: the identity must be reproducible
 * from a seed so byte assertions in the tests are stable. */
static uint32_t next_rand(void)
{
    return dl__rand_u32();
}

static uint32_t rand_below(uint32_t n)
{
    return n ? next_rand() % n : 0;
}

void dl_identity_generate(const dl_profile *profile, uint32_t seed, dl_identity *out)
{
    char serial[64];
    size_t prefix_len;

    memset(out, 0, sizeof(*out));
    if (!profile) {
        return;
    }
    dl_srand(seed ? seed : dl__rand_u32());

    /* did = didPrefix + 6 random digits */
    snprintf(out->did, sizeof(out->did), "%s%06u", profile->did_prefix,
             (unsigned)(1 + rand_below(999999u)));

    /* psn = psnPrefix + digits up to 18 characters total */
    prefix_len = strlen(profile->psn_prefix);
    if (prefix_len > sizeof(serial) - 1) {
        prefix_len = sizeof(serial) - 1;
    }
    memcpy(serial, profile->psn_prefix, prefix_len);
    serial[prefix_len] = 0;
    while (strlen(serial) < 18) {
        size_t n = strlen(serial);
        serial[n] = (char)('0' + rand_below(10));
        serial[n + 1] = 0;
    }
    snprintf(out->psn, sizeof(out->psn), "%s", serial);

    /* locally administered unicast MAC */
    {
        uint8_t mac[6];
        mac[0] = (uint8_t)((rand_below(256) & 0xFC) | 0x02);
        for (int i = 1; i < 6; i++) {
            mac[i] = (uint8_t)rand_below(256);
        }
        snprintf(out->mac_bt, sizeof(out->mac_bt), "%02X:%02X:%02X:%02X:%02X:%02X",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }

    snprintf(out->sku, sizeof(out->sku), "%s", profile->sku ? profile->sku : "");
    snprintf(out->key, sizeof(out->key), "0123456789ABCDEF");
    snprintf(out->fsn, sizeof(out->fsn), "QEMU1502P%05u", (unsigned)rand_below(100000u));
}

int dl_mac_valid(const char *mac)
{
    if (!mac) {
        return 0;
    }
    for (int i = 0; i < 6; i++) {
        int hi, lo;
        if (i > 0) {
            if (*mac++ != ':') {
                return 0;
            }
        }
        hi = *mac++;
        lo = *mac++;
        if (!isxdigit((unsigned char)hi) || !isxdigit((unsigned char)lo)) {
            return 0;
        }
    }
    return *mac == 0;
}

int dl_identity_validate(const dl_identity *identity)
{
    if (!identity) {
        return DL_ERR_INVALID;
    }
    if (!*identity->did) {
        dl_set_error("device ID (ro.factory.did) is empty");
        return DL_ERR_INVALID;
    }
    for (const char *p = identity->did; *p; p++) {
        if (!isdigit((unsigned char)*p)) {
            dl_set_error("device ID must be decimal digits");
            return DL_ERR_INVALID;
        }
    }
    {
        size_t n = strlen(identity->psn);
        if (n < 1 || n > 31) {
            dl_set_error("serial (ro.factory.psn) must be 1..31 characters");
            return DL_ERR_INVALID;
        }
    }
    if (!dl_mac_valid(identity->mac_bt)) {
        dl_set_error("Bluetooth address must look like XX:XX:XX:XX:XX:XX");
        return DL_ERR_INVALID;
    }
    return DL_OK;
}

int dl_identity_get(const dl_identity *identity, const char *kind, char *out, size_t cap)
{
    const char *v = NULL;
    int n;

    if (!identity || !kind || !out) {
        return DL_ERR_INVALID;
    }
    if (!strcmp(kind, "did")) {
        v = identity->did;
    } else if (!strcmp(kind, "psn")) {
        v = identity->psn;
    } else if (!strcmp(kind, "mac_bt") || !strcmp(kind, "mac")) {
        v = identity->mac_bt;
    } else if (!strcmp(kind, "sku")) {
        v = identity->sku;
    } else if (!strcmp(kind, "key")) {
        v = identity->key;
    } else if (!strcmp(kind, "fsn")) {
        v = identity->fsn;
    } else {
        return DL_ERR_NOT_FOUND;
    }
    n = snprintf(out, cap, "%s", v ? v : "");
    return (n < 0 || (size_t)n >= cap) ? DL_ERR_INVALID : DL_OK;
}

int dl_identity_set(dl_identity *identity, const char *kind, const char *value)
{
    char *dst = NULL;
    size_t cap = 0;

    if (!identity || !kind || !value) {
        return DL_ERR_INVALID;
    }
    if (!strcmp(kind, "did")) {
        dst = identity->did;
        cap = sizeof(identity->did);
    } else if (!strcmp(kind, "psn")) {
        dst = identity->psn;
        cap = sizeof(identity->psn);
    } else if (!strcmp(kind, "mac_bt") || !strcmp(kind, "mac")) {
        dst = identity->mac_bt;
        cap = sizeof(identity->mac_bt);
    } else if (!strcmp(kind, "sku")) {
        dst = identity->sku;
        cap = sizeof(identity->sku);
    } else if (!strcmp(kind, "key")) {
        dst = identity->key;
        cap = sizeof(identity->key);
    } else if (!strcmp(kind, "fsn")) {
        dst = identity->fsn;
        cap = sizeof(identity->fsn);
    } else {
        return DL_ERR_NOT_FOUND;
    }
    if (strlen(value) >= cap) {
        dl_set_error("%s is too long", kind);
        return DL_ERR_INVALID;
    }
    snprintf(dst, cap, "%s", value);
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Bind state                                                          */
/* ------------------------------------------------------------------ */

void dl_bind_generate(uint32_t seed, dl_bind *out)
{
    memset(out, 0, sizeof(*out));
    dl_srand(seed ? seed : dl__rand_u32());
    out->bound = 1;
    for (int i = 0; i < 16; i++) {
        snprintf(out->auth_key + 2 * i, 3, "%02x", (unsigned)rand_below(256));
        snprintf(out->beacon_key + 2 * i, 3, "%02x", (unsigned)rand_below(256));
        snprintf(out->ir_key + 2 * i, 3, "%02x", (unsigned)rand_below(256));
    }
    snprintf(out->user_id, sizeof(out->user_id), "10000001");
    snprintf(out->seqnum, sizeof(out->seqnum), "1");
}

static int is_hex32(const char *s)
{
    size_t n = strlen(s);
    if (n != 32) {
        return 0;
    }
    for (size_t i = 0; i < n; i++) {
        if (!isxdigit((unsigned char)s[i])) {
            return 0;
        }
    }
    return 1;
}

int dl_bind_validate(const dl_bind *bind)
{
    if (!bind || !bind->bound) {
        return DL_OK;
    }
    if (!is_hex32(bind->auth_key) || !is_hex32(bind->beacon_key) || !is_hex32(bind->ir_key)) {
        dl_set_error("bind keys must be 32 hex digits (16 bytes)");
        return DL_ERR_INVALID;
    }
    if (!*bind->user_id) {
        dl_set_error("bind user ID is empty");
        return DL_ERR_INVALID;
    }
    return DL_OK;
}

/* Companion MAC: BindInfo.companionAddress C0:FF:EE:00:00:01 reversed, hex. */
static const char *companion_mac_reversed(void)
{
    return "010000eeffc0";
}

/* ------------------------------------------------------------------ */
/* Persistence (deviceDir/identity.properties)                         */
/* ------------------------------------------------------------------ */

static int write_props(FILE *f, const char *key, const char *value)
{
    if (value && *value) {
        return fprintf(f, "%s=%s\n", key, value) < 0 ? DL_ERR_IO : DL_OK;
    }
    return DL_OK;
}

int dl_identity_save(const dl_identity *identity, const dl_bind *bind, const char *path)
{
    FILE *f;

    if (!identity || !path) {
        return DL_ERR_INVALID;
    }
    f = fopen(path, "wb");
    if (!f) {
        dl_set_error("cannot write %s", path);
        return DL_ERR_IO;
    }
    write_props(f, "ro.factory.did", identity->did);
    write_props(f, "ro.factory.psn", identity->psn);
    write_props(f, "ro.factory.mac_bt", identity->mac_bt);
    write_props(f, "ro.factory.sku", identity->sku);
    write_props(f, "ro.factory.key", identity->key);
    write_props(f, "ro.factory.fsn", identity->fsn);
    if (bind && bind->bound) {
        write_props(f, "persist.miwear_token_key", bind->auth_key);
        write_props(f, "persist.miwear_user_id", bind->user_id);
        write_props(f, "persist.miwear_beacon_key", bind->beacon_key);
        write_props(f, "persist.miwear_ir_key", bind->ir_key);
        write_props(f, "persist.miwear_seqnum", bind->seqnum);
    }
    if (fclose(f) != 0) {
        dl_set_error("cannot close %s", path);
        return DL_ERR_IO;
    }
    return DL_OK;
}

int dl_identity_load(dl_identity *identity, dl_bind *bind, const char *path)
{
    FILE *f;
    char line[512];

    if (!identity || !path) {
        return DL_ERR_INVALID;
    }
    memset(identity, 0, sizeof(*identity));
    if (bind) {
        memset(bind, 0, sizeof(*bind));
    }
    f = fopen(path, "rb");
    if (!f) {
        dl_set_error("cannot read %s", path);
        return DL_ERR_NOT_FOUND;
    }
    while (fgets(line, sizeof(line), f)) {
        char *eq = strchr(line, '=');
        char *nl;
        if (!eq) {
            continue;
        }
        *eq = 0;
        nl = eq + 1;
        while (*nl == ' ' || *nl == '\t') {
            nl++;
        }
        {
            char *end = nl + strlen(nl);
            while (end > nl && (end[-1] == '\n' || end[-1] == '\r' || end[-1] == ' ')) {
                *--end = 0;
            }
        }
        if (!strcmp(line, "ro.factory.did")) {
            snprintf(identity->did, sizeof(identity->did), "%s", nl);
        } else if (!strcmp(line, "ro.factory.psn")) {
            snprintf(identity->psn, sizeof(identity->psn), "%s", nl);
        } else if (!strcmp(line, "ro.factory.mac_bt")) {
            snprintf(identity->mac_bt, sizeof(identity->mac_bt), "%s", nl);
        } else if (!strcmp(line, "ro.factory.sku")) {
            snprintf(identity->sku, sizeof(identity->sku), "%s", nl);
        } else if (!strcmp(line, "ro.factory.key")) {
            snprintf(identity->key, sizeof(identity->key), "%s", nl);
        } else if (!strcmp(line, "ro.factory.fsn")) {
            snprintf(identity->fsn, sizeof(identity->fsn), "%s", nl);
        } else if (bind && !strcmp(line, "persist.miwear_token_key")) {
            snprintf(bind->auth_key, sizeof(bind->auth_key), "%s", nl);
            bind->bound = 1;
        } else if (bind && !strcmp(line, "persist.miwear_user_id")) {
            snprintf(bind->user_id, sizeof(bind->user_id), "%s", nl);
        } else if (bind && !strcmp(line, "persist.miwear_beacon_key")) {
            snprintf(bind->beacon_key, sizeof(bind->beacon_key), "%s", nl);
        } else if (bind && !strcmp(line, "persist.miwear_ir_key")) {
            snprintf(bind->ir_key, sizeof(bind->ir_key), "%s", nl);
        } else if (bind && !strcmp(line, "persist.miwear_seqnum")) {
            snprintf(bind->seqnum, sizeof(bind->seqnum), "%s", nl);
        }
    }
    fclose(f);
    if (!*identity->did || !*identity->psn || !*identity->mac_bt) {
        dl_set_error("%s is not a complete identity file", path);
        return DL_ERR_FORMAT;
    }
    return DL_OK;
}

/* ------------------------------------------------------------------ */
/* Factory property text (what /factory.prop holds)                    */
/* ------------------------------------------------------------------ */

/* Writes the properties Mirage ImageBuilder.factoryProperties produces:
 * did, psn, MAC (uppercase), then the bind persist.* keys, then sku. */
int dl_factory_text(const dl_identity *identity, const dl_bind *bind,
                    char *out, size_t cap)
{
    int n = 0;
    int w;

    if (!identity || !out || cap == 0) {
        return DL_ERR_INVALID;
    }
    out[0] = 0;
#define APPEND_PROP(k, v)                                                        \
    do {                                                                         \
        w = snprintf(out + n, cap - (size_t)n, "%s=%s\n", (k), (v));              \
        if (w < 0 || (size_t)w >= cap - (size_t)n) {                             \
            dl_set_error("factory properties do not fit");                       \
            return DL_ERR_NOSPACE;                                               \
        }                                                                        \
        n += w;                                                                  \
    } while (0)

    APPEND_PROP("ro.factory.did", identity->did);
    APPEND_PROP("ro.factory.psn", identity->psn);
    APPEND_PROP("ro.factory.mac_bt", identity->mac_bt);
    if (bind && bind->bound) {
        APPEND_PROP("persist.miwear_token_key", bind->auth_key);
        APPEND_PROP("persist.miwear_user_id", bind->user_id);
        APPEND_PROP("persist.miwear_beacon_key", bind->beacon_key);
        APPEND_PROP("persist.miwear_ir_key", bind->ir_key);
        APPEND_PROP("persist.miwear_seqnum", bind->seqnum);
        APPEND_PROP("persist.setup_wizard", "1");
        APPEND_PROP("persist.miwear_companion_device_type", "0");
        APPEND_PROP("persist.miwear_companion_device_mac", companion_mac_reversed());
    }
    if (identity->sku[0]) {
        APPEND_PROP("ro.factory.sku", identity->sku);
    }
#undef APPEND_PROP
    return DL_OK;
}
