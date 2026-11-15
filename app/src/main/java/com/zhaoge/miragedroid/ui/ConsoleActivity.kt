package com.zhaoge.miragedroid.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zhaoge.miragedroid.MirageApp
import com.zhaoge.miragedroid.data.SessionState
import com.zhaoge.miragedroid.emu.SessionListener
import com.zhaoge.miragedroid.emu.SessionManager
import com.zhaoge.miragedroid.net.RfbFrame

/**
 * 全屏控制台（NSH 终端）：设备 console UART 的原始文本流 + 命令输入。
 * 数据来自 [SessionManager] 的 NshClient 回滚缓冲，离开页面也不丢。
 */
class ConsoleActivity : Activity(), SessionListener {
    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val MAX_CHARS = 200000
    }

    private lateinit var app: MirageApp
    private lateinit var session: SessionManager
    private lateinit var deviceId: String
    private lateinit var statusText: TextView
    private lateinit var consoleView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

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
        setContentView(buildContent())
        session.addListener(this)
        refresh()
    }

    override fun onDestroy() {
        session.removeListener(this)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = Ui.page(this, 12f)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@ConsoleActivity, 12f)
            layoutParams = params
        }
        topBar.addView(Ui.text(this, "‹", 30f, Ui.TEXT_DIM).apply {
            setPadding(0, 0, Ui.dp(this@ConsoleActivity, 14f), Ui.dp(this@ConsoleActivity, 8f))
            Ui.clickable(this) { finish() }
        })
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(Ui.text(this, "控制台", 18f, Ui.TEXT, bold = true))
        statusText = Ui.text(this, "", 11.5f, Ui.TEXT_DIM)
        titleColumn.addView(statusText)
        topBar.addView(titleColumn)
        topBar.addView(
            Ui.smallButton(this, "清屏") {
                session.consoleClearLocal()
                consoleView.text = ""
            },
        )
        root.addView(topBar)

        scroll = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            params.topMargin = Ui.dp(this@ConsoleActivity, 10f)
            layoutParams = params
            background = Ui.rounded(this@ConsoleActivity, 0xFF0A0D11.toInt(), 14f)
            isFillViewport = true
            val padding = Ui.dp(this@ConsoleActivity, 12f)
            setPadding(padding, padding, padding, padding)
        }
        consoleView = Ui.text(this, "", 11.5f, 0xFFB7C4CF.toInt(), mono = true)
        scroll.addView(consoleView)
        root.addView(scroll)

        val quickScroll = HorizontalScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@ConsoleActivity, 8f)
            layoutParams = params
            isHorizontalScrollBarEnabled = false
        }
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickRow.addView(Ui.text(this, "常用：", 12f, Ui.TEXT_DIM))
        for (command in listOf("help", "ps", "ls /data/quickapp", "free")) {
            quickRow.addView(
                Ui.smallButton(this, command) {
                    if (session.state != SessionState.RUNNING) {
                        Ui.toast(this, "设备未运行，无法发送命令")
                    } else {
                        session.consoleSend(command)
                    }
                }.apply {
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    params.marginEnd = Ui.dp(this@ConsoleActivity, 6f)
                    layoutParams = params
                },
            )
        }
        quickScroll.addView(quickRow)
        root.addView(quickScroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Ui.dp(this@ConsoleActivity, 8f)
            layoutParams = params
        }
        input = EditText(this).apply {
            hint = "输入命令后回车（如 ps / ls / cat /data/quickapp/apps.json）"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            background = Ui.rounded(this@ConsoleActivity, Ui.SURFACE_HIGH, 12f)
            setPadding(
                Ui.dp(this@ConsoleActivity, 12f),
                Ui.dp(this@ConsoleActivity, 8f),
                Ui.dp(this@ConsoleActivity, 12f),
                Ui.dp(this@ConsoleActivity, 8f),
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                sendCurrentInput()
                true
            }
        }
        inputRow.addView(input)
        inputRow.addView(Ui.smallButton(this, "发送") { sendCurrentInput() })
        root.addView(inputRow)

        root.addView(
            Ui.text(
                this,
                "控制台是设备 console UART 的原始字节流；部分机型/构建可能没有交互式 NSH。",
                11f,
                Ui.TEXT_DIM,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = Ui.dp(this@ConsoleActivity, 8f)
                layoutParams = params
            },
        )
        return root
    }

    private fun sendCurrentInput() {
        val line = input.text.toString()
        if (line.isBlank()) return
        if (session.state != SessionState.RUNNING) {
            Ui.toast(this, "设备未运行，无法发送命令")
            return
        }
        session.consoleSend(line)
        input.setText("")
    }

    private fun refresh() {
        val device = session.device()
        val state = session.state
        statusText.text = "${device?.name ?: "设备"} · ${state.title} · UART ${session.profile().consoleUart}"
        val history = session.consoleHistory()
        val text = if (history.length > MAX_CHARS) "…" + history.takeLast(MAX_CHARS) else history
        consoleView.text = if (text.isEmpty()) "（还没有控制台输出）" else text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onSessionStateChanged(session: SessionManager) {
        refresh()
    }

    override fun onSessionFrame(session: SessionManager, frame: RfbFrame) {
        // 控制台页不需要画面
    }

    override fun onSessionConsole(session: SessionManager, text: String) {
        val view = text.let { if (it.length > MAX_CHARS) "…" + it.takeLast(MAX_CHARS) else it }
        val atBottom = isScrolledToBottom()
        consoleView.text = if (view.isEmpty()) "（还没有控制台输出）" else view
        if (atBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.bottom - Ui.dp(this, 32f)
    }
}
