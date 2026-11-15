package com.zhaoge.miragedroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.zhaoge.miragedroid.MirageApp
import com.zhaoge.miragedroid.data.DeviceProfile
import com.zhaoge.miragedroid.data.DeviceProfiles
import com.zhaoge.miragedroid.data.SessionState
import com.zhaoge.miragedroid.data.VirtualDevice
import com.zhaoge.miragedroid.data.formatBytes

/**
 * 设备库（首页）：仿小米运动健康的设备页——卡片列表、状态、新建设备。
 *
 * 新建流程：选机型 → 起名 → 选官方 OTA 包 → 后台解包 + 生成存储 → 进设备页。
 * 没有模拟核心 / devicelib 的构建也能正常打开，只给出明确提示。
 */
class HubActivity : Activity() {
    companion object {
        private const val REQUEST_OTA = 4301
    }

    private lateinit var app: MirageApp
    private lateinit var listContainer: LinearLayout
    private var progressDialog: AlertDialog? = null
    private var pendingDevice: VirtualDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.prepareWindow(window)
        app = MirageApp.of(this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        dismissProgress()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 布局

    private fun buildContent(): View {
        val root = Ui.page(this, 16f)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@HubActivity, 18f)
            layoutParams = params
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(Ui.text(this, "幻影模拟器", 25f, Ui.TEXT, bold = true))
        titleColumn.addView(
            Ui.text(this, "设备库 · 本地模拟，不联网", 12.5f, Ui.TEXT_DIM).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@HubActivity, 4f)
                layoutParams = params
            },
        )
        header.addView(titleColumn)
        header.addView(Ui.smallButton(this, "设置") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(header)

        if (!app.qemuAvailable()) {
            root.addView(environmentBanner())
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isFillViewport = true
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@HubActivity, 6f), 0, Ui.dp(this@HubActivity, 24f))
        }
        scroll.addView(
            listContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll)
        return root
    }

    private fun environmentBanner(): View {
        val location = app.qemuLocation()
        val missing = location.file == null
        val title = if (missing) "未检测到模拟核心" else "模拟核心无法执行"
        val detail = when {
            missing -> "当前 APK 不含 libqemu-system-arm.so，设备可以管理但无法启动。" +
                "请安装含模拟核心的构建（CI 的 qemu 产物打进来之后即可）。"
            !location.executable -> "找到 ${location.file?.name}，但系统拒绝执行：" +
                "${location.file?.absolutePath}。可在「设置 → 模拟核心」查看自检详情。"
            else -> ""
        }
        val accent = if (missing) Ui.ACCENT else Ui.ERROR
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@HubActivity, Ui.ACCENT_DIM, 14f, accent, 1f)
            val padding = Ui.dp(this@HubActivity, 14f)
            setPadding(padding, padding, padding, padding)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@HubActivity, 14f)
            layoutParams = params
            addView(Ui.text(this@HubActivity, title, 14f, accent, bold = true))
            addView(
                Ui.text(this@HubActivity, detail, 12.5f, Ui.TEXT_DIM).apply {
                    val p = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    p.topMargin = Ui.dp(this@HubActivity, 5f)
                    layoutParams = p
                },
            )
        }
    }

    // ------------------------------------------------------------ 设备列表

    private fun refresh() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val devices = app.store.devices().sortedBy { it.createdAt }
        if (devices.isEmpty()) {
            listContainer.addView(emptyCard())
        } else {
            listContainer.addView(Ui.sectionTitle(this, "我的设备（${devices.size}）"))
            for (device in devices) {
                listContainer.addView(deviceCard(device))
            }
        }
        listContainer.addView(Ui.spacer(this, 10f))
        listContainer.addView(
            Ui.button(this, "＋  添加设备", primary = true) { showProfilePicker() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        listContainer.addView(
            Ui.text(
                this,
                "固件与设备数据只保存在本机。请使用你自己拥有设备的官方 OTA 包。",
                11.5f,
                Ui.TEXT_DIM,
            ).apply {
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@HubActivity, 14f)
                layoutParams = params
            },
        )
    }

    private fun emptyCard(): View {
        val card = Ui.card(this, Ui.SURFACE, 18f, 20f)
        card.addView(Ui.text(this, "还没有设备", 17f, Ui.TEXT, bold = true))
        card.addView(
            Ui.text(
                this,
                "添加一台虚拟设备（REDMI Watch 6 / 小米手环 11 等），导入你从官方渠道取得的 OTA 固件，" +
                    "即可在本机离线运行它的完整系统。",
                13f,
                Ui.TEXT_DIM,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@HubActivity, 8f)
                layoutParams = params
            },
        )
        return card.apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@HubActivity, 14f)
            layoutParams = params
        }
    }

    private fun deviceCard(device: VirtualDevice): View {
        val profile = device.profile()
        val session = app.sessions.peek(device.id)
        val state = session?.state ?: SessionState.STOPPED
        val firmware = device.firmwareId?.let { app.store.firmware(it) }

        val card = Ui.card(this, Ui.SURFACE, 18f, 16f)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Ui.dp(this, 10f)
        card.layoutParams = params

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(Ui.text(this, device.name, 17f, Ui.TEXT, bold = true))
        infoColumn.addView(
            Ui.text(
                this,
                "${profile.name} · ${profile.screen.width}×${profile.screen.height} · ${profile.storage.kindTitle}",
                12f,
                Ui.TEXT_DIM,
            ).apply {
                val p = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                p.topMargin = Ui.dp(this@HubActivity, 3f)
                layoutParams = p
            },
        )
        topRow.addView(infoColumn)
        topRow.addView(Ui.chip(this, state.title, Ui.stateColor(state)))
        card.addView(topRow)

        val firmwareText = when {
            firmware == null -> "未导入固件 · 点击进入后导入"
            device.storageBuiltAt <= 0L -> "${firmware.displayName} · 待生成存储"
            else -> "${firmware.displayName} · ${formatBytes(firmware.sizeBytes)}"
        }
        card.addView(
            Ui.text(this, firmwareText, 12.5f, if (firmware == null) Ui.WARN else Ui.TEXT_DIM).apply {
                val p = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                p.topMargin = Ui.dp(this@HubActivity, 10f)
                layoutParams = p
            },
        )

        if (state == SessionState.ERROR && !session?.lastError.isNullOrBlank()) {
            card.addView(
                Ui.text(
                    this,
                    session!!.lastError!!.lineSequence().firstOrNull().orEmpty().take(160),
                    11.5f,
                    Ui.ERROR,
                ).apply {
                    val p = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    p.topMargin = Ui.dp(this@HubActivity, 6f)
                    layoutParams = p
                },
            )
        }

        Ui.clickable(card) {
            startActivity(
                Intent(this, DeviceActivity::class.java).putExtra(DeviceActivity.EXTRA_DEVICE_ID, device.id),
            )
        }
        card.setOnLongClickListener {
            showDeviceMenu(device)
            true
        }
        return card
    }

    private fun showDeviceMenu(device: VirtualDevice) {
        val options = arrayOf("重命名", "导入/更换固件", "重新生成设备存储", "删除设备")
        AlertDialog.Builder(this)
            .setTitle(device.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(device)
                    1 -> pickOta(device)
                    2 -> provisionOnly(device)
                    3 -> confirmDelete(device)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(device: VirtualDevice) {
        val input = EditText(this).apply {
            setText(device.name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名设备")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { device.profile().name }
                app.store.upsertDevice(device.copy(name = name))
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(device: VirtualDevice) {
        AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("将删除「${device.name}」及其本机镜像、日志。固件文件会保留。")
            .setPositiveButton("删除") { _, _ ->
                app.sessions.remove(device.id)
                app.store.deleteDevice(device.id, deleteFiles = true)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 新建设备

    private fun showProfilePicker() {
        val profiles = DeviceProfiles.builtins
        val labels = profiles.map { profile ->
            val tags = buildList {
                add(profile.family.title)
                add("${profile.screen.width}×${profile.screen.height}")
                if (profile.storage.kindTitle != "仅 NOR") add(profile.storage.kindTitle)
                if (profile.id == "p65" || profile.id == "q66") add("首批实测")
            }
            "${profile.name}\n${tags.joinToString(" · ")}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择机型")
            .setItems(labels) { _, which -> showNameDialog(profiles[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNameDialog(profile: DeviceProfile) {
        val input = EditText(this).apply {
            setText(profile.name)
            hint = "设备名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("新建 ${profile.name}")
            .setMessage("面板 ${profile.screen.width}×${profile.screen.height} · qemu board ${profile.qemuBoard}")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { profile.name }
                val device = VirtualDevice.create(profile, name, null)
                app.store.upsertDevice(device)
                app.store.updateSettings { it.copy(lastDeviceId = device.id) }
                refresh()
                askImportFirmware(device)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askImportFirmware(device: VirtualDevice) {
        AlertDialog.Builder(this)
            .setTitle("导入官方 OTA 固件")
            .setMessage(
                "需要从官方渠道取得的 OTA 包（vela_ap.bin 所在文件）。导入后会在本机解包并生成 " +
                    "NOR/NAND 设备镜像，全程离线。\n\n现在选择文件？",
            )
            .setPositiveButton("选择文件") { _, _ -> pickOta(device) }
            .setNegativeButton("以后再说") { _, _ -> openDevice(device) }
            .show()
    }

    private fun pickOta(device: VirtualDevice) {
        pendingDevice = device
        if (!app.deviceLibAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("无法导入固件")
                .setMessage(
                    "当前构建不含 devicelib（libmiragedroid.so），无法解包 OTA / 生成镜像。" +
                        "请安装含模拟核心与 devicelib 的构建。",
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        }
        try {
            startActivityForResult(intent, REQUEST_OTA)
        } catch (error: Throwable) {
            Ui.toast(this, "没有可用的文件选择器：${error.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OTA) return
        val device = pendingDevice ?: return
        if (resultCode != RESULT_OK) {
            openDevice(device)
            return
        }
        val uri: Uri = data?.data ?: return
        importFirmware(device, uri)
    }

    private fun importFirmware(device: VirtualDevice, uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在导入固件")
            .setMessage("准备中…")
            .setCancelable(false)
            .create()
        progressDialog = dialog
        dialog.show()

        Thread {
            val importResult = app.importer.importOta(uri) { message -> setDialogMessage(dialog, message) }
            if (importResult.firmware == null) {
                runOnUiThread {
                    dismissProgress()
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("导入失败")
                        .setMessage(importResult.message)
                        .setPositiveButton("知道了") { _, _ -> openDevice(device) }
                        .show()
                }
                return@Thread
            }
            val firmware = importResult.firmware
            val latest = app.store.device(device.id) ?: device
            val provision = app.importer.provision(latest, firmware, latest.profile()) { message ->
                setDialogMessage(dialog, message)
            }
            runOnUiThread {
                dismissProgress()
                if (isFinishing) return@runOnUiThread
                if (provision.ok) {
                    Ui.toast(this, provision.message)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("生成设备存储失败")
                        .setMessage(provision.message)
                        .setPositiveButton("知道了", null)
                        .show()
                }
                openDevice(app.store.device(device.id) ?: latest)
            }
        }.apply { name = "FirmwareImport" }.start()
    }

    private fun setDialogMessage(dialog: AlertDialog, message: String) {
        runOnUiThread {
            if (progressDialog === dialog && dialog.isShowing) dialog.setMessage(message)
        }
    }

    private fun dismissProgress() {
        progressDialog?.let { if (it.isShowing) it.dismiss() }
        progressDialog = null
    }

    /** 只重建存储（固件已导入时）。 */
    private fun provisionOnly(device: VirtualDevice) {
        val firmware = device.firmwareId?.let { app.store.firmware(it) }
        if (firmware == null) {
            Ui.toast(this, "还没有导入固件")
            askImportFirmware(device)
            return
        }
        if (!app.deviceLibAvailable()) {
            Ui.toast(this, "当前构建不含 devicelib，无法生成设备存储")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在生成设备存储")
            .setMessage("准备中…")
            .setCancelable(false)
            .create()
        progressDialog = dialog
        dialog.show()
        Thread {
            val result = app.importer.provision(device, firmware, device.profile()) { message ->
                setDialogMessage(dialog, message)
            }
            runOnUiThread {
                dismissProgress()
                if (isFinishing) return@runOnUiThread
                Ui.toast(this, result.message)
                refresh()
            }
        }.start()
    }

    private fun openDevice(device: VirtualDevice) {
        if (isFinishing) return
        startActivity(
            Intent(this, DeviceActivity::class.java).putExtra(DeviceActivity.EXTRA_DEVICE_ID, device.id),
        )
    }

}
