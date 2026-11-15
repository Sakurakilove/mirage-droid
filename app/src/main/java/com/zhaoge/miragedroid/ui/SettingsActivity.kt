package com.zhaoge.miragedroid.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zhaoge.miragedroid.MirageApp
import com.zhaoge.miragedroid.data.NativeCore
import com.zhaoge.miragedroid.data.formatBytes
import com.zhaoge.miragedroid.emu.QemuLocator

/**
 * 全局设置 / 关于：模拟核心自检、通用开关、数据管理、日志导出、开源许可与免责声明。
 */
class SettingsActivity : Activity() {
    private lateinit var app: MirageApp
    private lateinit var coreStatus: TextView
    private lateinit var coreDetail: TextView
    private lateinit var coreVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.prepareWindow(window)
        app = MirageApp.of(this)
        setContentView(buildContent())
        refreshCore()
        detectCoreVersion()
    }

    private fun buildContent(): View {
        val root = Ui.page(this, 16f)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@SettingsActivity, 12f)
            layoutParams = params
        }
        topBar.addView(Ui.text(this, "‹", 30f, Ui.TEXT_DIM).apply {
            setPadding(0, 0, Ui.dp(this@SettingsActivity, 14f), Ui.dp(this@SettingsActivity, 8f))
            Ui.clickable(this) { finish() }
        })
        topBar.addView(Ui.text(this, "设置", 20f, Ui.TEXT, bold = true))
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 6f), 0, Ui.dp(this@SettingsActivity, 24f))
        }

        // ---- 模拟核心 ----
        column.addView(Ui.sectionTitle(this, "模拟核心"))
        val coreCard = Ui.card(this)
        coreStatus = Ui.text(this, "", 16f, Ui.TEXT, bold = true)
        coreCard.addView(coreStatus)
        coreDetail = Ui.text(this, "", 12.5f, Ui.TEXT_DIM).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@SettingsActivity, 6f)
            layoutParams = params
        }
        coreCard.addView(coreDetail)
        coreVersion = Ui.text(this, "qemu 版本：检测中…", 12f, Ui.TEXT_DIM).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@SettingsActivity, 8f)
            layoutParams = params
        }
        coreCard.addView(coreVersion)
        coreCard.addView(Ui.spacer(this, 10f))
        coreCard.addView(
            Ui.button(this, "重新检测", primary = false) {
                refreshCore()
                detectCoreVersion()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        column.addView(coreCard)

        // ---- 通用 ----
        column.addView(Ui.sectionTitle(this, "通用"))
        val settings = app.store.settings()
        val generalCard = Ui.card(this)
        generalCard.addView(
            Ui.switchRow(
                this,
                "打开设备页时保持屏幕常亮",
                "模拟手表时更方便观察画面",
                settings.keepScreenOn,
            ) { checked ->
                app.store.updateSettings { it.copy(keepScreenOn = checked) }
            },
        )
        generalCard.addView(Ui.divider(this, 4f, 4f))
        generalCard.addView(
            Ui.switchRow(
                this,
                "进入设备页自动启动模拟",
                "关闭后需要手动点「启动设备」",
                settings.autoStartSession,
            ) { checked ->
                app.store.updateSettings { it.copy(autoStartSession = checked) }
            },
        )
        column.addView(generalCard)

        // ---- 数据 ----
        column.addView(Ui.sectionTitle(this, "数据"))
        val dataCard = Ui.card(this)
        val devices = app.store.devices()
        val firmwares = app.store.firmwares()
        dataCard.addView(Ui.text(this, "${devices.size} 台设备 · ${firmwares.size} 份固件", 15f, Ui.TEXT))
        dataCard.addView(
            Ui.text(
                this,
                "库目录：${app.store.root.absolutePath}",
                11.5f,
                Ui.TEXT_DIM,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@SettingsActivity, 6f)
                layoutParams = params
            },
        )
        dataCard.addView(Ui.spacer(this, 10f))
        dataCard.addView(
            Ui.button(this, "导出日志（zip）", primary = false) {
                exportLogs()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        column.addView(dataCard)

        // ---- 关于 ----
        column.addView(Ui.sectionTitle(this, "关于"))
        val aboutCard = Ui.card(this)
        aboutCard.addView(Ui.text(this, "幻影模拟器 MirageDroid", 17f, Ui.TEXT, bold = true))
        aboutCard.addView(
            Ui.text(this, "版本 ${versionName()}", 12.5f, Ui.TEXT_DIM).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@SettingsActivity, 4f)
                layoutParams = params
            },
        )
        aboutCard.addView(Ui.divider(this, 12f, 4f))
        aboutCard.addView(aboutLine("用途", "在 Android 手机上离线运行你自有设备的固件（qemu 全系统模拟）。"))
        aboutCard.addView(aboutLine("存储", "固件、设备镜像与日志只保存在本机 App 私有目录，不联网、不内嵌官方 APK 资源。"))
        aboutCard.addView(aboutLine("模拟核心", "qemu-miwear（GPL-2.0），board 定义来自 Mirage 的 DeviceProfile。"))
        aboutCard.addView(aboutLine("参考实现", "Mirage / MirageKit（AGPL-3.0）——VNC、QMP、NSH 与镜像布局的参考。"))
        aboutCard.addView(aboutLine("许可", "本仓库源码以 GPL-3.0 发布，详见仓库 LICENSE。"))
        aboutCard.addView(aboutLine("免责", "仅用于模拟你自己拥有或获授权测试的设备固件；请遵守当地法律与厂商条款。"))
        column.addView(aboutCard)

        column.addView(Ui.spacer(this, 16f))
        scroll.addView(column)
        root.addView(scroll)
        return root
    }

    private fun aboutLine(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = Ui.dp(this@SettingsActivity, 5f)
        setPadding(0, padding, 0, padding)
        addView(Ui.text(this@SettingsActivity, title, 12.5f, Ui.ACCENT, bold = true))
        addView(
            Ui.text(this@SettingsActivity, body, 12.5f, Ui.TEXT_DIM).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@SettingsActivity, 3f)
                layoutParams = params
            },
        )
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (error: Throwable) {
        "unknown"
    }

    private fun refreshCore() {
        val location = app.qemuLocation()
        val executable = location.file
        if (executable == null) {
            coreStatus.text = "模拟核心：缺失"
            coreStatus.setTextColor(Ui.WARN)
            coreDetail.text = location.detail +
                "\n\n设备管理与固件导入可用，但无法启动模拟。请安装含模拟核心的 APK（jniLibs）。"
        } else if (!location.executable) {
            coreStatus.text = "模拟核心：存在但不可执行"
            coreStatus.setTextColor(Ui.ERROR)
            coreDetail.text = location.detail +
                "\n\n设备管理与固件导入可用；启动会失败。若 APK 已内置核心，" +
                "请确认安装时解压了原生库（本 App 已开 useLegacyPackaging）。"
        } else {
            coreStatus.text = "模拟核心：可用"
            coreStatus.setTextColor(Ui.OK)
            coreDetail.text = buildString {
                append(location.detail)
                val size = executable.length()
                if (size > 0) append("\n大小：").append(formatBytes(size))
                val devicelib = NativeCore.version()
                append("\ndevicelib：").append(if (devicelib.isNullOrEmpty()) "不可用" else devicelib)
                append("\n设备库目录：").append(app.store.root.absolutePath)
            }
        }
    }

    private fun detectCoreVersion() {
        coreVersion.text = "qemu 版本：检测中…"
        val executable = app.qemuExecutable()
        if (executable == null) {
            coreVersion.text = "qemu 版本：—（核心缺失）"
            return
        }
        Thread {
            // 真机自检：真正 exec 一次 qemu --version，失败时把 stderr 尾巴带回来
            val info = QemuLocator.versionDetailed(executable, cacheDir)
            app.qemuVersion = info.version
            app.qemuVersionChecked = true
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                coreVersion.text = buildString {
                    if (info.version.isNullOrBlank()) {
                        append("qemu 版本：执行 --version 失败")
                    } else {
                        append("qemu 版本：").append(info.version)
                    }
                    if (!info.error.isNullOrBlank()) {
                        append("\n").append(info.error)
                    }
                }
            }
        }.apply { name = "QemuVersionProbe" }.start()
    }

    private fun exportLogs() {
        val info = buildString {
            appendLine("MirageDroid 日志导出")
            appendLine("appVersion=${versionName()}")
            appendLine("qemuAvailable=${app.qemuAvailable()}")
            appendLine("qemuVersion=${app.qemuVersion ?: "-"}")
            appendLine("devicelibVersion=${NativeCore.version() ?: "-"}")
            appendLine("library=${app.store.root.absolutePath}")
            appendLine("devices=${app.store.devices().size} firmwares=${app.store.firmwares().size}")
            appendLine("sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.DEVICE}")
        }
        val file = LogExport.exportAllLogs(this, app.store, info)
        if (file == null) {
            Ui.toast(this, "导出失败（没有可导出的日志或存储不可写）")
        } else {
            Ui.toast(this, "已导出：${file.absolutePath}")
        }
    }
}
