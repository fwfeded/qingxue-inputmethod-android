// 独立按键触点：普通点击、上滑副符号、长按备选与有背压的连续退格。
package dev.qingstudy.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.PopupMenu
import kotlin.math.abs

class KeyboardKey(context: Context) : Button(context) {
    var secondary: String = ""
    var secondaryColor: Int = 0
    var onSwipe: (() -> Unit)? = null
    var repeatAction: (() -> Unit)? = null
    var canRepeat: () -> Boolean = { true }
    var onFeedback: (() -> Unit)? = null
    var feedbackKind = "letter"
    private var touchFeedbackSent = false
    init { isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false }
    private val density = resources.displayMetrics.density
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var originX = 0f
    private var originY = 0f
    private var pointer = -1
    private var swiped = false
    private var longFired = false
    private var canceled = false
    private var popup: PopupMenu? = null
    private val hold = Runnable {
        if (pointer != -1 && isPressed && isEnabled && isShown) {
            if (repeatAction != null) {
                longFired = true
                repeatDelete.run()
            } else longFired = performLongClick()
        }
    }
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (pointer == -1 || canceled || !isShown) return
            // 引擎忙时跳过本拍，避免松手后积压一串退格。
            if (isEnabled && canRepeat()) { onFeedback?.invoke(); repeatAction?.invoke() }
            postDelayed(this, 85)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        super.onDraw(canvas)
        // TextView 会在绘制文字时裁剪到 padding 内；副标使用完整按键坐标。
        canvas.restoreToCount(checkpoint)
        if (secondary.isEmpty()) return
        small.color = secondaryColor
        small.textSize = minOf(10 * resources.displayMetrics.scaledDensity, height * .22f)
        canvas.drawText(secondary, width / 2f, 4 * density - small.fontMetrics.top, small)
    }

    fun showChoices(values: List<String>, select: (String) -> Unit) {
        popup?.dismiss()
        popup = PopupMenu(context, this).also { menu ->
            for (value in values) menu.menu.add(value).setOnMenuItemClickListener { select(value); true }
            menu.setOnDismissListener { popup = null }
            menu.show()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { cancelInteraction(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopTouch()
                pointer = event.getPointerId(0); originX = event.x; originY = event.y
                canceled = false; longFired = false; swiped = false; isPressed = true
                touchFeedbackSent = true; onFeedback?.invoke()
                drawableHotspotChanged(event.x, event.y)
                if (repeatAction != null || isLongClickable) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> { canceled = true; stopTouch() }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0 || canceled) return true
                val x = event.getX(index); val y = event.getY(index)
                if (!longFired && onSwipe != null && originY - y >= maxOf(14 * density, height * .28f) && abs(x - originX) < width * .8f) {
                    swiped = true; isPressed = false; removeCallbacks(hold)
                } else if (!swiped && (x < 0 || x > width || y < 0 || y > height)) {
                    canceled = true; stopTouch()
                }
            }
            MotionEvent.ACTION_UP -> {
                val valid = pointer != -1 && !canceled
                val swipe = swiped; val held = longFired
                stopTouch()
                try { if (valid) { if (swipe) onSwipe?.invoke() else if (!held) performClick() } }
                finally { touchFeedbackSent = false }
            }
            MotionEvent.ACTION_CANCEL -> { stopTouch(); touchFeedbackSent = false }
            else -> return super.onTouchEvent(event)
        }
        return true
    }
    private fun stopTouch() {
        pointer = -1; isPressed = false
        removeCallbacks(hold); removeCallbacks(repeatDelete)
    }
    fun cancelInteraction() { stopTouch(); touchFeedbackSent = false; popup?.dismiss(); popup = null }
    override fun onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) stopTouch()
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        if (!isVisible) cancelInteraction()
        super.onVisibilityAggregated(isVisible)
    }
    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (!touchFeedbackSent) onFeedback?.invoke()
        return super.performClick()
    }
}
