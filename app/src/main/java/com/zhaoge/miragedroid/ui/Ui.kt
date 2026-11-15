package com.zhaoge.miragedroid.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zhaoge.miragedroid.data.SessionState

/**
 * 纯代码构建界面用的小工具：颜色、圆角背景、文字、卡片、按钮、开关行。
 *
 * App 只用 android.* 原生控件（不用 XML 布局、不用 AppCompat），这样每一处
 * 界面改动都能在本地用 android.jar 做类型检查，减少 CI 返工。
 */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171C22.toInt()
    const val SURFACE_HIGH = 0xFF222A33.toInt()
    const val TEXT = 0xFFF2F5F7.toInt()
    const val TEXT_DIM = 0xFF9AA4AF.toInt()
    const val ACCENT = 0xFFFF6A00.toInt()
    const val ACCENT_DIM = 0xFF3A2418.toInt()
    const val OK = 0xFF4CAF50.toInt()
    const val WARN = 0xFFFFC107.toInt()
    const val ERROR = 0xFFEF5350.toInt()
    const val DIVIDER = 0xFF2A323C.toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(
        context: Context,
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f,
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        drawable.setColor(fillColor)
        if (strokeColor != null && strokeWidthDp > 0f) {
            drawable.setStroke(dp(context, strokeWidthDp), strokeColor)
        }
        return drawable
    }

    /** 给可点击的 View 加一层水波纹（API 26+ 有 foreground）。 */
    fun ripple(context: Context, content: GradientDrawable): RippleDrawable {
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.RECTANGLE
        mask.cornerRadius = dp(context, 18f).toFloat()
        val color = ColorStateList.valueOf(0x33FFFFFF)
        return RippleDrawable(color, content, mask)
    }

    fun text(
        context: Context,
        text: CharSequence,
        sizeSp: Float,
        color: Int = TEXT,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        this.textSize = sizeSp
        this.setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (mono) {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = sizeSp
        }
        includeFontPadding = false
        setLineSpacing(0f, 1.08f)
    }

    fun clickable(view: View, onClick: (View) -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick(it) }
    }

    fun card(
        context: Context,
        fill: Int = SURFACE,
        radiusDp: Float = 18f,
        paddingDp: Float = 16f,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, fill, radiusDp)
        val padding = dp(context, paddingDp)
        setPadding(padding, padding, padding, padding)
    }

    /** 主按钮（橙色填充）/ 次按钮（深灰填充）。 */
    fun button(
        context: Context,
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: (View) -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFF16100C.toInt() else TEXT)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = ripple(
            context,
            rounded(
                context,
                if (primary) ACCENT else SURFACE_HIGH,
                14f,
                if (primary) null else DIVIDER,
                1f,
            ),
        )
        val horizontal = dp(context, 18f)
        val vertical = dp(context, 11f)
        setPadding(horizontal, vertical, horizontal, vertical)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        clickable(this) { if (enabled) onClick(it) }
    }

    fun smallButton(context: Context, label: String, enabled: Boolean = true, onClick: (View) -> Unit): TextView =
        button(context, label, primary = false, enabled = enabled, onClick = onClick).apply {
            textSize = 13f
            val horizontal = dp(context, 12f)
            val vertical = dp(context, 8f)
            setPadding(horizontal, vertical, horizontal, vertical)
        }

    fun divider(context: Context, paddingTopDp: Float = 12f, paddingBottomDp: Float = 12f): View =
        View(context).apply {
            setBackgroundColor(DIVIDER)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
            params.topMargin = dp(context, paddingTopDp)
            params.bottomMargin = dp(context, paddingBottomDp)
            layoutParams = params
        }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp))
    }

    fun hSpacer(context: Context, widthDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), 1)
    }

    fun sectionTitle(context: Context, title: String): TextView = text(context, title, 13f, TEXT_DIM, bold = true).apply {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(context, 18f)
        params.bottomMargin = dp(context, 8f)
        layoutParams = params
        letterSpacing = 0.06f
        text = title.uppercase()
    }

    fun row(
        context: Context,
        title: String,
        summary: String? = null,
        trailing: View? = null,
        onClick: ((View) -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vertical = dp(context, 12f)
            setPadding(0, vertical, 0, vertical)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(text(context, title, 15f, TEXT))
        if (!summary.isNullOrEmpty()) {
            column.addView(
                text(context, summary, 12.5f, TEXT_DIM).apply {
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    params.topMargin = dp(context, 3f)
                    layoutParams = params
                },
            )
        }
        row.addView(column)
        if (trailing != null) row.addView(trailing)
        if (onClick != null) clickable(row) { onClick(it) }
        return row
    }

    fun switchRow(
        context: Context,
        title: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val toggle = android.widget.Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, 0xFF56606B.toInt()),
            )
        }
        return row(context, title, summary, toggle)
    }

    fun chip(context: Context, label: String, color: Int): TextView = text(context, label, 11.5f, color, bold = true).apply {
        background = rounded(context, (color and 0x00FFFFFF) or 0x22000000, 8f, color, 1f)
        val horizontal = dp(context, 8f)
        val vertical = dp(context, 3f)
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    fun stateColor(state: SessionState): Int = when (state) {
        SessionState.RUNNING -> OK
        SessionState.LAUNCHING, SessionState.STOPPING -> WARN
        SessionState.ERROR -> ERROR
        SessionState.STOPPED -> TEXT_DIM
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 让内容避开状态栏/导航栏（Android 15 起强制 edge-to-edge）。 */
    fun applySystemBarInsets(view: View) {
        val initialTop = view.paddingTop
        view.setOnApplyWindowInsetsListener { target, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            target.setPadding(target.paddingLeft, top + initialTop, target.paddingRight, bottom)
            insets
        }
    }

    fun prepareWindow(window: Window) {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.setBackgroundDrawableResource(android.R.color.black)
    }

    /** 通用页面根：垂直 LinearLayout + 深色背景 + 内边距。 */
    fun page(context: Context, paddingDp: Float = 16f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        val padding = dp(context, paddingDp)
        setPadding(padding, 0, padding, 0)
        applySystemBarInsets(this)
    }
}
