package com.zhaoge.miragedroid.data;

/**
 * devicelib 的 JNI 入口（实现：app/src/main/cpp/miragedroid_jni.c，库名 miragedroid）。
 *
 * 用纯 Java 静态方法而不是 Kotlin object，是为了让 C 侧的符号名稳定可预测：
 * `Java_com_zhaoge_miragedroid_data_Devicelib_<method>`。
 *
 * 调用方必须先通过 [NativeCore] 检查库是否加载成功——M0/M1 阶段 APK 里可能
 * 还没有 libmiragedroid.so，直接调用 native 方法会抛 UnsatisfiedLinkError。
 */
public final class Devicelib {
    /** 成功返回码。 */
    public static final int OK = 0;

    private Devicelib() {
    }

    /**
     * 解包官方 OTA zip 到 [outDir]。
     *
     * @return 0 = 成功；负数 = 错误码（具体见 [lastError]）
     */
    public static native int importOta(String zipPath, String outDir);

    /**
     * 读取 [firmwareDir]/vela_ap.bin 的 boot info。
     *
     * @return JSON 字符串：{"product":"miwear.watch.p65","chip":"best1502p",
     * "version":"...","flashSize":16777216,"products":["..."]}；失败返回 null
     */
    public static native String firmwareInfo(String firmwareDir);

    /**
     * 按 [profileId] 生成设备存储镜像到 [deviceDir]
     * （nor.img / sensor-nor.img / nand.img / emmc.img，按机型决定生成哪些）。
     *
     * @return 0 = 成功；负数 = 错误码（具体见 [lastError]）
     */
    public static native int buildStorage(String profileId, String firmwareDir, String deviceDir);

    /** devicelib 版本号。 */
    public static native String version();

    /** 最近一次失败的说明（无失败返回空串）。 */
    public static native String lastError();
}
