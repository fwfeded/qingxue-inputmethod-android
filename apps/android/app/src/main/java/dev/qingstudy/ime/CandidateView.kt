// 自绘双行候选：中文决定宽度，完整释义保留给长按与无障碍节点。
package dev.qingstudy.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

class CandidateView(context: Context, private val chinese: String, private val gloss: String,
    private val compact: Boolean = false, private val fresh: Boolean = false,
    private val appearance: KeyboardAppearance = KeyboardAppearance.read(context),
    private val first: Boolean = false) : View(context) {
    private val density = resources.displayMetrics.density
    private val palette = KeyboardPalette(context, appearance)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = appearance.textPixels(context, appearance.candidateSize)
        color = if (first) palette.onAccent else palette.ink
    }
    private val subtitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = appearance.textPixels(context, appearance.glossSize)
        color = if (first) palette.onAccent else palette.muted
    }
    init {
        contentDescription = "候选 $chinese，${gloss.ifEmpty { "暂无英文释义" }}"
        isClickable = true
        isFocusable = true
        background = palette.surface(if (first) palette.accent else palette.background, true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (title.measureText(chinese) + 20 * density).coerceIn(68 * density, 168 * density).toInt()
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize((appearance.candidateHeight(context) * density).toInt(), heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = title.fontMetrics
        val bottom = subtitle.fontMetrics
        val linesHeight = top.bottom - top.top + if (gloss.isEmpty()) 0f else bottom.bottom - bottom.top + 2 * density
        val firstBaseline = (height - linesHeight) / 2 - top.top
        val available = (width - 20 * density).coerceAtLeast(0f)
        canvas.drawText(TextUtils.ellipsize(chinese, title, available, TextUtils.TruncateAt.END).toString(),
            10 * density, firstBaseline, title)
        if (gloss.isNotEmpty()) canvas.drawText(TextUtils.ellipsize(gloss, subtitle, available, TextUtils.TruncateAt.END).toString(),
            10 * density, firstBaseline + top.bottom + 2 * density - bottom.top, subtitle)
        if (fresh) canvas.drawCircle(width - 5 * density, 5 * density, 2 * density, subtitle)
    }
}
