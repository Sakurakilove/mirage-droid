/* miragedroid_jni — Android bindings for devlib.
 *
 * Java (com.zhaoge.miragedroid.data.Devicelib):
 *   static native int    importOta(String zipPath, String outDir);
 *   static native String firmwareInfo(String firmwareDir);
 *   static native int    buildStorage(String profileId, String firmwareDir, String deviceDir);
 *   static native String version();
 *   static native String lastError();
 *
 * The heavy calls (buildStorage writes the ~528 MiB NAND image) are
 * blocking: call them from a background thread, not the UI thread.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devlib.h"

#define MIrage_JNI_VERSION "0.1.0"

static const char *jstr(JNIEnv *env, jstring s, char *buf, size_t cap)
{
    const char *utf;
    if (!s) {
        buf[0] = 0;
        return buf;
    }
    utf = (*env)->GetStringUTFChars(env, s, NULL);
    if (!utf) {
        buf[0] = 0;
        return buf;
    }
    snprintf(buf, cap, "%s", utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return buf;
}

static jstring jresult(JNIEnv *env, const char *text)
{
    return (*env)->NewStringUTF(env, text ? text : "");
}

/* Directory holding vela_ap.bin: firmwareDir itself or its single child. */
static int find_ap(JNIEnv *env, const char *firmware_dir, char *out, size_t cap)
{
    char path[4096];
    (void)env;
    snprintf(path, sizeof(path), "%s/vela_ap.bin", firmware_dir);
    {
        FILE *f = fopen(path, "rb");
        if (f) {
            fclose(f);
            snprintf(out, cap, "%s", path);
            return 0;
        }
    }
    return -1;
}

/* JSON string escaping. */
static void json_str(char *out, size_t cap, const char *in)
{
    size_t n = 0;
    if (!cap) {
        return;
    }
    for (; in && *in && n + 7 < cap; in++) {
        unsigned char c = (unsigned char)*in;
        if (c == '"' || c == '\\') {
            out[n++] = '\\';
            out[n++] = (char)c;
        } else if (c < 0x20) {
            n += (size_t)snprintf(out + n, cap - n, "\\u%04x", c);
        } else {
            out[n++] = (char)c;
        }
    }
    out[n] = 0;
}

JNIEXPORT jint JNICALL
Java_com_zhaoge_miragedroid_data_Devicelib_importOta(JNIEnv *env, jclass cls,
                                                     jstring zip_path, jstring out_dir)
{
    char zip[4096], out[4096];
    dl_import_report report;
    int rc;
    (void)cls;
    jstr(env, zip_path, zip, sizeof(zip));
    jstr(env, out_dir, out, sizeof(out));
    rc = dl_ota_import(zip, out, &report);
    if (rc != DL_OK) {
        dl_set_error("OTA import failed: %s", dl_last_error());
    }
    return (jint)rc;
}

JNIEXPORT jstring JNICALL
Java_com_zhaoge_miragedroid_data_Devicelib_firmwareInfo(JNIEnv *env, jclass cls,
                                                        jstring firmware_dir)
{
    char dir[4096], ap[4096], product[64], chip[64], version[64], build_date[64];
    char products[16 * 48];
    size_t used = 0;
    dl_ap_info info;
    char *json;
    jstring ret;
    (void)cls;

    jstr(env, firmware_dir, dir, sizeof(dir));
    if (find_ap(env, dir, ap, sizeof(ap)) != 0) {
        dl_set_error("no vela_ap.bin in %s", dir);
        return jresult(env, "{}");
    }
    if (dl_ap_read_info(ap, &info) != DL_OK) {
        return jresult(env, "{}");
    }
    json_str(product, sizeof(product),
             info.product_count ? info.products[0] : "");
    json_str(chip, sizeof(chip), info.chip);
    json_str(version, sizeof(version), info.version);
    json_str(build_date, sizeof(build_date), info.build_date);
    products[0] = 0;
    for (int i = 0; i < info.product_count; i++) {
        char esc[64];
        json_str(esc, sizeof(esc), info.products[i]);
        used += (size_t)snprintf(products + used, sizeof(products) - used, "%s\"%s\"",
                                 i ? "," : "", esc);
        if (used >= sizeof(products)) {
            break;
        }
    }
    json = malloc(4096);
    if (!json) {
        return jresult(env, "{}");
    }
    snprintf(json, 4096,
             "{\"product\":\"%s\",\"products\":[%s],\"chip\":\"%s\","
             "\"version\":\"%s\",\"buildDate\":\"%s\",\"flashSize\":%llu}",
             product, products, chip, version, build_date,
             (unsigned long long)(info.has_flash_size ? info.flash_size : 0));
    ret = jresult(env, json);
    free(json);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_zhaoge_miragedroid_data_Devicelib_buildStorage(JNIEnv *env, jclass cls,
                                                        jstring profile_id,
                                                        jstring firmware_dir,
                                                        jstring device_dir)
{
    char id[64], fw[4096], dev[4096];
    char identity_path[4096];
    const dl_profile *profile;
    dl_identity identity;
    dl_bind bind;
    dl_provision_options options;
    dl_verify_result verify;
    uint32_t seed;
    int rc;
    (void)cls;

    jstr(env, profile_id, id, sizeof(id));
    jstr(env, firmware_dir, fw, sizeof(fw));
    jstr(env, device_dir, dev, sizeof(dev));
    profile = dl_profile_by_id(id);
    if (!profile) {
        dl_set_error("unknown device profile '%s'", id);
        return (jint)DL_ERR_INVALID;
    }
    /* Keep the identity stable across rebuilds: reuse identity.properties
     * when the device directory already has one. */
    seed = dl_crc32(dev, strlen(dev));
    snprintf(identity_path, sizeof(identity_path), "%s/identity.properties", dev);
    memset(&identity, 0, sizeof(identity));
    memset(&bind, 0, sizeof(bind));
    {
        FILE *f = fopen(identity_path, "rb");
        if (f) {
            fclose(f);
            if (dl_identity_load(&identity, &bind, identity_path) != DL_OK) {
                dl_identity_generate(profile, seed, &identity);
                dl_bind_generate(seed ^ 0x9E3779B9u, &bind);
            }
        } else {
            dl_identity_generate(profile, seed, &identity);
            dl_bind_generate(seed ^ 0x9E3779B9u, &bind);
        }
    }
    memset(&options, 0, sizeof(options));
    options.firmware_dir = fw;
    options.device_dir = dev;
    options.bind_config_dir = NULL; /* the templates are embedded in libmiragedroid */
    options.seed_bind = 1;
    rc = dl_provision(profile, &identity, &bind, &options, &verify);
    if (rc != DL_OK) {
        dl_set_error("buildStorage failed: %s", dl_last_error());
    }
    return (jint)rc;
}

JNIEXPORT jstring JNICALL
Java_com_zhaoge_miragedroid_data_Devicelib_version(JNIEnv *env, jclass cls)
{
    (void)cls;
    return jresult(env, "miragedroid-devicelib " MIrage_JNI_VERSION);
}

JNIEXPORT jstring JNICALL
Java_com_zhaoge_miragedroid_data_Devicelib_lastError(JNIEnv *env, jclass cls)
{
    (void)cls;
    return jresult(env, dl_last_error());
}

/* JNI_OnLoad: nothing to do, the library has no global state to bind. */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}
