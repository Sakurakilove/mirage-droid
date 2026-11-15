package com.zhaoge.miragedroid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.zhaoge.miragedroid.data.ScreenShape
import com.zhaoge.miragedroid.data.ScreenSpec
import com.zhaoge.miragedroid.net.RfbFrame

/**
 * 设备屏幕舞台：把 VNC 帧按面板宽高比缩放绘制成设备造型（圆角矩形 / 圆形），
 * 并把手指坐标换算成面板像素比例的触摸注入。
 *
 * 绘制策略：面板尺寸（p65 432×514 / q66 212×520）非常小，等比放大到可用空间，
 * 用 clipPath 裁出圆角/圆形轮廓，比给整个 View 做圆角更贴近真机观感。
 */
class ScreenStageView(context: Context) : View(context) {
    interface TouchListener {
        fun onPanelTouch(pressed: Boolean, xRatio: Float, yRatio: Float)
    }

    var screenSpec: ScreenSpec = ScreenSpec(432, 514, ScreenShape.ROUNDED_RECT, 48)
        set(value) {
            field = value
            invalidate()
        }

    var touchListener: TouchListener? = null

    private var frame: RfbFrame? = null
    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2E3A45.toInt()
        strokeWidth = 2f
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7683.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val placeholderTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB7C0CA.toInt()
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val stageRect = RectF()
    private val clipPath = Path()

    private var placeholderTitle = "等待画面"
    private var placeholderHint = ""
    private var screenOn = true

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
    }

    /** 更新画面（在主线程调用）。 */
    fun setFrame(newFrame: RfbFrame) {
        frame = newFrame
        val current = bitmap
        if (current == null || current.width != newFrame.width || current.height != newFrame.height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(newFrame.width, newFrame.height, Bitmap.Config.ARGB_8888)
        }
        try {
            bitmap?.setPixels(newFrame.pixels, 0, newFrame.width, 0, 0, newFrame.width, newFrame.height)
        } catch (_: IllegalArgumentException) {
            bitmap?.recycle()
            bitmap = null
        }
        invalidate()
    }

    fun clearFrame() {
        frame = null
        bitmap?.recycle()
        bitmap = null
        invalidate()
    }

    /** 熄屏/未连接时显示占位文案。 */
    fun setPlaceholder(title: String, hint: String = "") {
        placeholderTitle = title
        placeholderHint = hint
        screenOn = false
        invalidate()
    }

    fun setScreenActive(active: Boolean) {
        screenOn = active
        invalidate()
    }

    private fun computeStageRect(): RectF {
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val panelWidth = screenSpec.width.toFloat()
        val panelHeight = screenSpec.height.toFloat()
        val scale = minOf(availableWidth / panelWidth, availableHeight / panelHeight)
        val drawWidth = panelWidth * scale
        val drawHeight = panelHeight * scale
        val left = paddingLeft + (availableWidth - drawWidth) / 2f
        val top = paddingTop + (availableHeight - drawHeight) / 2f
        stageRect.set(left, top, left + drawWidth, top + drawHeight)
        return stageRect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = computeStageRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val radius = if (screenSpec.shape == ScreenShape.CIRCLE) {
            minOf(rect.width(), rect.height()) / 2f
        } else {
            screenSpec.cornerRadius.toFloat() / screenSpec.width.toFloat() * rect.width()
        }

        // 面板底：模拟设备边框
        panelPaint.color = 0xFF07090C.toInt()
        canvas.drawRoundRect(
            RectF(rect.left - 6f, rect.top - 6f, rect.right + 6f, rect.bottom + 6f),
            radius + 6f,
            radius + 6f,
            panelPaint,
        )

        clipPath.reset()
        if (screenSpec.shape == ScreenShape.CIRCLE) {
            clipPath.addOval(rect, Path.Direction.CW)
        } else {
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clipPath)
        val current = bitmap
        if (current != null && screenOn) {
            canvas.drawBitmap(current, null, rect, bitmapPaint)
        } else {
            canvas.drawColor(0xFF101418.toInt())
            if (!screenOn) {
                canvas.drawText(
                    placeholderTitle,
                    rect.centerX(),
                    rect.centerY() - 10f,
                    placeholderTitlePaint,
                )
                if (placeholderHint.isNotEmpty()) {
                    canvas.drawText(
                        placeholderHint,
                        rect.centerX(),
                        rect.centerY() + 34f,
                        placeholderPaint,
                    )
                }
            }
        }
        canvas.restore()

        borderPaint.strokeWidth = if (screenSpec.shape == ScreenShape.CIRCLE) 2f else 3f
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = computeStageRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return false
        val xRatio = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val yRatio = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchListener?.onPanelTouch(true, xRatio, yRatio)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchListener?.onPanelTouch(true, xRatio, yRatio)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchListener?.onPanelTouch(false, xRatio, yRatio)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
