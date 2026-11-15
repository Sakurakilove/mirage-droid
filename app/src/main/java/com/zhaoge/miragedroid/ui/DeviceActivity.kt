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
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.zhaoge.miragedroid.MirageApp
import com.zhaoge.miragedroid.data.DeviceProfile
import com.zhaoge.miragedroid.data.DeviceProfiles
import com.zhaoge.miragedroid.data.SessionState
import com.zhaoge.miragedroid.data.VirtualDevice
import com.zhaoge.miragedroid.data.formatBytes
import com.zhaoge.miragedroid.emu.SessionListener
import com.zhaoge.miragedroid.emu.SessionManager
import com.zhaoge.miragedroid.net.RfbFrame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备页：屏幕舞台（按 profile 面板绘制 + 触摸映射）+ 控制条 + 控制台 / 表盘 / 设置 tab。
 *
 * 会话由 [MirageApp.sessions] 持有：离开页面 QEMU 继续跑，回来直接续上。 
 */
class DeviceActivity : Activity(), SessionListener {
    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CONSOLE_PREVIEW_CHARS = 60000
        private const val REQUEST_OTA = 4401
    }

    private lateinit var app: MirageApp
    private lateinit var deviceId: String
    private lateinit var session: SessionManager
    private var device: VirtualDevice? = null
    private var profile: DeviceProfile = DeviceProfiles.P65

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusChip: TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var contentHost: FrameLayout

    private var screenView: ScreenStageView? = null
    private var screenOverlay: LinearLayout? = null
    private var screenOverlayTitle: TextView? = null
    private var screenOverlayHint: TextView? = null
    private var screenOverlayButton: TextView? = null
    private var batteryLabel: TextView? = null
    private var consoleText: TextView? = null
    private var consoleScroll: ScrollView? = null
    private var consoleInput: EditText? = null

    private var currentTab = 0
    private val tabTitles = listOf("屏幕", "控制台", "表盘", "设置")
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.prepareWindow(window)
        app = MirageApp.of(this)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            finish()
            return
        }
        val manager = app.sessions.get(deviceId) ?: run {
            Ui.toast(this, "设备不存在")
            finish()
            return
        }
        session = manager
        device = session.device()
        profile = session.profile()
        if (app.store.settings().keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContentView(buildContent())
        session.addListener(this)
        selectTab(0)
        if (session.state == SessionState.STOPPED && app.store.settings().autoStartSession) {
            session.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
    }

    override fun onDestroy() {
        dismissProgress()
        session.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 布局

    private fun buildContent(): View {
        val root = Ui.page(this, 14f)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 12f)
            layoutParams = params
        }
        topBar.addView(Ui.text(this, "‹", 30f, Ui.TEXT_DIM).apply {
            setPadding(0, 0, Ui.dp(this@DeviceActivity, 14f), Ui.dp(this@DeviceActivity, 8f))
            Ui.clickable(this) { finish() }
        })
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleText = Ui.text(this, "设备", 18f, Ui.TEXT, bold = true)
        subtitleText = Ui.text(this, "", 11.5f, Ui.TEXT_DIM)
        titleColumn.addView(titleText)
        titleColumn.addView(subtitleText)
        topBar.addView(titleColumn)
        statusChip = Ui.chip(this, SessionState.STOPPED.title, Ui.TEXT_DIM)
        topBar.addView(statusChip)
        root.addView(topBar)

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 10f)
            layoutParams = params
        }
        for (index in tabTitles.indices) {
            val tab = Ui.text(this, tabTitles[index], 14f, Ui.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@DeviceActivity, 10f), 0, Ui.dp(this@DeviceActivity, 10f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                Ui.clickable(this) { selectTab(index) }
            }
            tabBar.addView(tab)
        }
        root.addView(tabBar)
        root.addView(Ui.divider(this, 0f, 0f))

        contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(contentHost)
        return root
    }

    private fun selectTab(index: Int) {
        currentTab = index
        for (child in 0 until tabBar.childCount) {
            val tab = tabBar.getChildAt(child) as TextView
            tab.setTextColor(if (child == index) Ui.ACCENT else Ui.TEXT_DIM)
            tab.setTypeface(tab.typeface, if (child == index) Typeface.BOLD else Typeface.NORMAL)
        }
        contentHost.removeAllViews()
        screenView = null
        screenOverlay = null
        screenOverlayTitle = null
        screenOverlayHint = null
        screenOverlayButton = null
        consoleText = null
        consoleScroll = null
        consoleInput = null
        batteryLabel = null
        val content: View = when (index) {
            0 -> buildScreenTab()
            1 -> buildConsoleTab()
            2 -> buildWatchfaceTab()
            else -> buildSettingsTab()
        }
        contentHost.addView(content)
    }

    // ------------------------------------------------------------ 屏幕 tab

    private fun buildScreenTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val stageHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val stage = ScreenStageView(this).apply {
            screenSpec = profile.screen
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            touchListener = object : ScreenStageView.TouchListener {
                override fun onPanelTouch(pressed: Boolean, xRatio: Float, yRatio: Float) {
                    session.touchRatio(xRatio, yRatio, pressed)
                }
            }
        }
        screenView = stage
        stageHost.addView(stage)

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(this@DeviceActivity, 0xCC0B0E12.toInt(), 18f)
            setPadding(
                Ui.dp(this@DeviceActivity, 24f),
                Ui.dp(this@DeviceActivity, 20f),
                Ui.dp(this@DeviceActivity, 24f),
                Ui.dp(this@DeviceActivity, 20f),
            )
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            layoutParams = params
        }
        val overlayTitle = Ui.text(this, "已关机", 16f, Ui.TEXT, bold = true)
        overlayTitle.gravity = Gravity.CENTER
        screenOverlayTitle = overlayTitle
        overlay.addView(overlayTitle)
        val overlayHint = Ui.text(this, "", 12f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 6f)
            layoutParams = params
        }
        screenOverlayHint = overlayHint
        overlay.addView(overlayHint)
        val overlayButton = Ui.button(this, "启动设备", primary = true) { onOverlayAction() }.apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 14f)
            layoutParams = params
        }
        screenOverlayButton = overlayButton
        overlay.addView(overlayButton)
        screenOverlay = overlay
        stageHost.addView(overlay)
        root.addView(stageHost)

        // ---- 控制条：按键 / 截图 / 关机 / 重启 ----
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 8f)
            layoutParams = params
        }
        val buttons = listOf(
            "上键" to { session.keyPress("home") },
            "电源" to { session.keyPress("pgdn") },
            "截图" to { takeScreenshot() },
            "关机" to { confirmShutdown() },
            "重启" to { confirmRestart() },
        )
        for ((label, action) in buttons) {
            val button = Ui.smallButton(this, label) { action() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = Ui.dp(this@DeviceActivity, 4f)
                    marginStart = Ui.dp(this@DeviceActivity, 4f)
                }
            }
            controls.addView(button)
        }
        root.addView(controls)

        // ---- 电量 ----
        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 8f)
            layoutParams = params
        }
        batteryLabel = Ui.text(this, "", 12f, Ui.TEXT_DIM)
        batteryRow.addView(batteryLabel)
        val seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 100
            progress = device?.batteryLevel ?: 80
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    batteryLabel?.text = "电量 $value%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    applyBattery(bar.progress)
                }
            })
        }
        batteryRow.addView(seekBar)
        root.addView(batteryRow)
        refreshBatteryLabel()
        refreshScreenOverlay()
        return root
    }

    private fun refreshBatteryLabel() {
        val current = device ?: return
        val charging = if (current.batteryCharging) "（充电中）" else ""
        batteryLabel?.text = "电量 ${current.batteryLevel}%$charging"
    }

    private fun applyBattery(level: Int) {
        session.applyBattery(level, device?.batteryCharging ?: true)
        val current = device
        if (current != null) {
            device = current.copy(batteryLevel = level)
            refreshBatteryLabel()
        }
    }

    private fun onOverlayAction() {
        when (session.state) {
            SessionState.STOPPED, SessionState.ERROR -> {
                val current = device
                val ready = current != null && current.firmwareId != null && current.storageBuiltAt > 0L
                if (ready) session.start() else finish()
            }
            SessionState.RUNNING -> session.stop()
            else -> {
            }
        }
    }

    private fun confirmShutdown() {
        if (!session.isActive()) {
            Ui.toast(this, "设备已关机")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("关机")
            .setMessage("向 QEMU 发送 quit 并结束模拟核心？")
            .setPositiveButton("关机") { _, _ -> session.stop() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRestart() {
        if (!session.isActive()) {
            session.start()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("重启")
            .setMessage("重启会重新启动 qemu 进程（设备内数据保留在镜像里）。")
            .setPositiveButton("重启") { _, _ -> session.restart() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun takeScreenshot() {
        if (session.state != SessionState.RUNNING) {
            Ui.toast(this, "设备未运行，无法截图")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(File(app.store.deviceDir(deviceId), "Screenshots"), "shot-$stamp.png")
        Ui.toast(this, "正在截图…")
        session.screenshot(target) { ok, message ->
            if (ok) {
                Ui.toast(this, "截图已保存：${target.name}")
            } else {
                Ui.toast(this, "截图失败：${message ?: "未知原因"}")
            }
        }
    }

    private fun refreshScreenOverlay() {
        val overlay = screenOverlay ?: return
        val title = screenOverlayTitle ?: return
        val hint = screenOverlayHint ?: return
        val button = screenOverlayButton ?: return
        val hasFirmware = device?.firmwareId != null
        val built = (device?.storageBuiltAt ?: 0L) > 0L
        when (session.state) {
            SessionState.RUNNING -> {
                if (session.latestFrame == null) {
                    overlay.visibility = View.VISIBLE
                    overlay.background = Ui.rounded(this, 0xCC0B0E12.toInt(), 18f)
                    title.text = "正在连接画面…"
                    hint.text = "qemu 已启动，VNC 画面稍后到达"
                    button.text = "关机"
                    button.visibility = View.VISIBLE
                } else {
                    overlay.visibility = View.GONE
                }
                screenView?.setScreenActive(true)
                session.latestFrame?.let { screenView?.setFrame(it) }
            }
            SessionState.LAUNCHING -> {
                overlay.visibility = View.VISIBLE
                title.text = "正在启动…"
                hint.text = "首次启动要加载固件，请稍候"
                button.text = "取消"
                button.visibility = View.VISIBLE
                screenView?.setPlaceholder("正在启动", "加载固件中…")
            }
            SessionState.STOPPING -> {
                overlay.visibility = View.VISIBLE
                title.text = "正在关机…"
                hint.text = ""
                button.visibility = View.GONE
            }
            SessionState.ERROR -> {
                overlay.visibility = View.VISIBLE
                title.text = "启动失败"
                val error = session.lastError ?: "未知错误"
                hint.text = error.take(420)
                button.text = "重试"
                button.visibility = View.VISIBLE
                screenView?.setPlaceholder("启动失败", "")
            }
            SessionState.STOPPED -> {
                overlay.visibility = View.VISIBLE
                title.text = when {
                    !hasFirmware -> "尚未导入固件"
                    !built -> "设备存储未生成"
                    else -> "已关机"
                }
                hint.text = when {
                    !hasFirmware -> "返回设备库导入官方 OTA 固件后再启动"
                    !built -> "重新生成设备存储后再启动"
                    else -> "${profile.name} · 触摸屏幕即可操作"
                }
                button.text = if (hasFirmware && built) "启动设备" else "返回设备库"
                button.visibility = View.VISIBLE
                screenView?.clearFrame()
            }
        }
        if (button.visibility == View.VISIBLE) {
            button.setOnClickListener { onOverlayAction() }
        }
    }

    // ------------------------------------------------------------ 控制台 tab

    private fun buildConsoleTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 8f)
            layoutParams = params
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            background = Ui.rounded(this@DeviceActivity, 0xFF0A0D11.toInt(), 14f)
            isFillViewport = true
            val padding = Ui.dp(this@DeviceActivity, 12f)
            setPadding(padding, padding, padding, padding)
        }
        val text = Ui.text(this, "", 11.5f, 0xFFB7C4CF.toInt(), mono = true)
        consoleText = text
        consoleScroll = scroll
        scroll.addView(text)
        root.addView(scroll)
        updateConsoleText(session.consoleHistory())

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 8f)
            layoutParams = params
        }
        val input = EditText(this).apply {
            hint = "输入 NSH 命令（如 ls /data/quickapp）"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            background = Ui.rounded(this@DeviceActivity, Ui.SURFACE_HIGH, 12f)
            setPadding(
                Ui.dp(this@DeviceActivity, 12f),
                Ui.dp(this@DeviceActivity, 8f),
                Ui.dp(this@DeviceActivity, 12f),
                Ui.dp(this@DeviceActivity, 8f),
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        consoleInput = input
        inputRow.addView(input)
        inputRow.addView(Ui.smallButton(this, "发送") {
            val line = input.text.toString()
            if (line.isNotBlank()) {
                session.consoleSend(line)
                input.setText("")
            }
        })
        root.addView(inputRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 8f)
            layoutParams = params
        }
        actions.addView(Ui.smallButton(this, "清屏") {
            session.consoleClearLocal()
            updateConsoleText("")
        })
        actions.addView(Ui.hSpacer(this, 8f))
        actions.addView(Ui.smallButton(this, "打开完整终端") {
            startActivity(
                Intent(this, ConsoleActivity::class.java).putExtra(ConsoleActivity.EXTRA_DEVICE_ID, deviceId),
            )
        })
        root.addView(actions)
        return root
    }

    private fun updateConsoleText(text: String) {
        val view = consoleText ?: return
        view.text = if (text.length > CONSOLE_PREVIEW_CHARS) "…" + text.takeLast(CONSOLE_PREVIEW_CHARS) else text
        consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ 表盘 tab

    private fun buildWatchfaceTab(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@DeviceActivity, 6f), 0, Ui.dp(this@DeviceActivity, 24f))
        }
        val firmware = session.firmware()
        val card = Ui.card(this)
        card.addView(Ui.text(this, "固件", 13f, Ui.TEXT_DIM, bold = true))
        card.addView(
            Ui.text(
                this,
                firmware?.displayName ?: "未导入固件",
                16f,
                Ui.TEXT,
                bold = true,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@DeviceActivity, 6f)
                layoutParams = params
            },
        )
        if (firmware != null) {
            card.addView(Ui.spacer(this, 8f))
            card.addView(infoLine("产品", firmware.product.ifEmpty { "未知" }))
            card.addView(infoLine("版本", firmware.version.ifEmpty { "未知" }))
            card.addView(infoLine("芯片", firmware.chip.ifEmpty { "未知" }))
            card.addView(infoLine("大小", formatBytes(firmware.sizeBytes)))
            card.addView(infoLine("导入时间", formatTime(firmware.importedAt)))
        }
        column.addView(card)

        val manageCard = Ui.card(this, Ui.SURFACE).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 12f)
            layoutParams = params
        }
        manageCard.addView(Ui.text(this, "表盘管理", 16f, Ui.TEXT, bold = true))
        manageCard.addView(
            Ui.text(
                this,
                "表盘管理（读取设备内的表盘列表、安装/删除第三方表盘）将在下一个里程碑接入：\n" +
                    "  · 通过控制台读取 /data/quickapp/apps.json 与表盘资源\n" +
                    "  · 通过 NSH 写入表盘包并触发系统刷新\n" +
                    "当前版本可在「控制台」里手动执行 NSH 命令。",
                12.5f,
                Ui.TEXT_DIM,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@DeviceActivity, 8f)
                layoutParams = params
            },
        )
        column.addView(manageCard)

        val infoCard = Ui.card(this, Ui.SURFACE).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 12f)
            layoutParams = params
        }
        infoCard.addView(Ui.text(this, "设备", 16f, Ui.TEXT, bold = true))
        infoCard.addView(Ui.spacer(this, 8f))
        infoCard.addView(infoLine("机型", "${profile.name}（${profile.id}）"))
        infoCard.addView(infoLine("面板", "${profile.screen.width}×${profile.screen.height}"))
        infoCard.addView(infoLine("存储", profile.storage.kindTitle))
        infoCard.addView(infoLine("控制台 UART", profile.consoleUart.toString()))
        infoCard.addView(infoLine("蓝牙地址", device?.bluetoothAddress ?: "未生成"))
        column.addView(infoCard)

        scroll.addView(column)
        return scroll
    }

    private fun infoLine(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val padding = Ui.dp(this@DeviceActivity, 3f)
        setPadding(0, padding, 0, padding)
        addView(
            Ui.text(this@DeviceActivity, label, 12.5f, Ui.TEXT_DIM).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0.36f,
                )
            },
        )
        addView(
            Ui.text(this@DeviceActivity, value, 12.5f, Ui.TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0.64f,
                )
            },
        )
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
    }

    // ------------------------------------------------------------ 设置 tab

    private fun buildSettingsTab(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@DeviceActivity, 6f), 0, Ui.dp(this@DeviceActivity, 24f))
        }

        val chargeRow = Ui.switchRow(
            this,
            "模拟充电",
            "qemu 的电池充电状态（写入设备设置，下次启动生效）",
            device?.batteryCharging ?: true,
        ) { checked ->
            val current = device
            if (current != null) {
                device = current.copy(batteryCharging = checked)
                app.store.upsertDevice(device!!)
                session.applyBattery(current.batteryLevel, checked)
                refreshBatteryLabel()
            }
        }
        column.addView(Ui.card(this).apply { addView(chargeRow) })

        val debugRow = Ui.switchRow(
            this,
            "调试模式（-S）",
            "启动时暂停 CPU 等待 gdb（需重新启动生效）",
            device?.shell ?: false,
        ) { checked ->
            val current = device ?: return@switchRow
            device = current.copy(shell = checked)
            app.store.upsertDevice(device!!)
            Ui.toast(this, "下次启动生效")
        }
        column.addView(Ui.card(this, Ui.SURFACE).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 12f)
            layoutParams = params
            addView(debugRow)
        })

        val actionsCard = Ui.card(this, Ui.SURFACE).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@DeviceActivity, 12f)
            layoutParams = params
        }
        actionsCard.addView(Ui.text(this, "维护", 16f, Ui.TEXT, bold = true))
        actionsCard.addView(Ui.spacer(this, 10f))
        actionsCard.addView(
            Ui.button(this, "导入 / 更换固件", primary = false) {
                pickOta()
            }.apply { layoutParams = fullWidthParams() },
        )
        actionsCard.addView(Ui.spacer(this, 8f))
        actionsCard.addView(
            Ui.button(this, "查看启动命令", primary = false) {
                showCommandLine()
            }.apply { layoutParams = fullWidthParams() },
        )
        actionsCard.addView(Ui.spacer(this, 8f))
        actionsCard.addView(
            Ui.button(this, "打开设备日志", primary = false) {
                showLogs()
            }.apply { layoutParams = fullWidthParams() },
        )
        actionsCard.addView(Ui.spacer(this, 8f))
        actionsCard.addView(
            Ui.button(this, "重新生成设备存储", primary = false) {
                regenerateStorage()
            }.apply { layoutParams = fullWidthParams() },
        )
        actionsCard.addView(Ui.spacer(this, 8f))
        actionsCard.addView(
            Ui.button(this, "删除设备", primary = false) {
                confirmDelete()
            }.apply { layoutParams = fullWidthParams() },
        )
        column.addView(actionsCard)
        scroll.addView(column)
        return scroll
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ------------------------------------------------------------ 固件 / 存储

    private fun pickOta() {
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
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importFirmware(uri)
    }

    private fun importFirmware(uri: Uri) {
        val target = device ?: session.device() ?: return
        val dialog = showProgressDialog("正在导入固件", "准备中…")
        Thread {
            val importResult = app.importer.importOta(uri) { message -> setDialogMessage(dialog, message) }
            val firmware = importResult.firmware
            if (firmware == null) {
                runOnUiThread {
                    dismissProgress()
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("导入失败")
                        .setMessage(importResult.message)
                        .setPositiveButton("知道了", null)
                        .show()
                }
                return@Thread
            }
            val latest = app.store.device(target.id) ?: target
            val provision = app.importer.provision(latest, firmware, latest.profile()) { message ->
                setDialogMessage(dialog, message)
            }
            runOnUiThread {
                dismissProgress()
                if (isFinishing) return@runOnUiThread
                device = app.store.device(target.id)
                refreshHeader()
                selectTab(currentTab)
                if (provision.ok) {
                    Ui.toast(this, provision.message)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("固件已导入，但生成存储失败")
                        .setMessage(provision.message)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.apply { name = "DeviceFirmwareImport" }.start()
    }

    private fun regenerateStorage() {
        val target = device ?: session.device()
        val firmware = session.firmware()
        if (target == null || firmware == null) {
            Ui.toast(this, "还没有导入固件，请先「导入 / 更换固件」")
            return
        }
        if (!app.deviceLibAvailable()) {
            Ui.toast(this, "当前构建不含 devicelib，无法生成设备存储")
            return
        }
        val dialog = showProgressDialog("正在重新生成设备存储", "准备中…")
        Thread {
            val result = app.importer.provision(target, firmware, target.profile()) { message ->
                setDialogMessage(dialog, message)
            }
            runOnUiThread {
                dismissProgress()
                if (isFinishing) return@runOnUiThread
                device = app.store.device(target.id)
                refreshHeader()
                selectTab(currentTab)
                Ui.toast(this, result.message)
            }
        }.apply { name = "DeviceProvision" }.start()
    }

    private fun showProgressDialog(title: String, message: String): AlertDialog {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .create()
        progressDialog = dialog
        dialog.show()
        return dialog
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

    private fun showCommandLine() {
        val command = session.qemuCommandLine() ?: "（尚未启动过，还没有命令行）"
        AlertDialog.Builder(this)
            .setTitle("qemu 启动命令")
            .setMessage(command)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showLogs() {
        val logs = session.logs()
        if (logs.isEmpty()) {
            Ui.toast(this, "还没有日志文件")
            return
        }
        val names = logs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("设备日志")
            .setItems(names) { _, which ->
                val file = logs[which]
                val content = try {
                    file.readText(Charsets.UTF_8).takeLast(20000)
                } catch (error: Throwable) {
                    "读取失败：${error.message}"
                }
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setMessage(content.ifEmpty { "（空文件）" })
                    .setPositiveButton("关闭", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("删除「${device?.name ?: "设备"}」及其本机镜像与日志？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                app.sessions.remove(deviceId)
                app.store.deleteDevice(deviceId, deleteFiles = true)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 会话回调

    override fun onSessionStateChanged(session: SessionManager) {
        device = session.device()
        refreshHeader()
        refreshScreenOverlay()
        refreshBatteryLabel()
        if (currentTab == 1) updateConsoleText(session.consoleHistory())
    }

    override fun onSessionFrame(session: SessionManager, frame: RfbFrame) {
        screenView?.setFrame(frame)
        if (session.state == SessionState.RUNNING) {
            screenOverlay?.visibility = View.GONE
        }
    }

    override fun onSessionConsole(session: SessionManager, text: String) {
        if (currentTab == 1) updateConsoleText(text)
    }

    private fun refreshHeader() {
        val current = device ?: session.device()
        if (current == null) {
            titleText.text = "设备已删除"
            return
        }
        titleText.text = current.name
        subtitleText.text = "${profile.name} · ${profile.screen.width}×${profile.screen.height}"
        val state = session.state
        statusChip.text = state.title
        statusChip.setTextColor(Ui.stateColor(state))
        statusChip.background = Ui.rounded(
            this,
            (Ui.stateColor(state) and 0x00FFFFFF) or 0x22000000,
            8f,
            Ui.stateColor(state),
            1f,
        )
        if (state == SessionState.ERROR && session.lastError != null) {
            subtitleText.text = session.lastError!!.lineSequence().firstOrNull() ?: profile.name
        }
    }
}
