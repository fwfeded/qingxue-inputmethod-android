//! 复用已确认主题卡片的缩略键帽和名称，不依赖外部图片或主题包。
package dev.qingstudy.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

internal class ThemeCard(context: Context, id: String, selected: Boolean) : View(context) {
    private val palette = KeyboardPalette(context, KeyboardAppearance(theme = id))
    private val name = KeyboardThemes.name(id)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    init {
        contentDescription = "主题 $name"; isSelected = selected; isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (selected) "已选中" else "未选中"
    }
    override fun onDraw(canvas: Canvas) {
        val d = density
        paint.style = Paint.Style.FILL; paint.color = palette.background
        rect.set(4*d, 4*d, width-4*d, height-4*d); canvas.drawRoundRect(rect, 10*d, 10*d, paint)
        if (isSelected) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*d; paint.color = palette.highlight
            canvas.drawRoundRect(rect, 10*d, 10*d, paint); paint.style = Paint.Style.FILL
        }
        paint.color = palette.ink; paint.textSize = 11*d
        canvas.drawText("你好   hello; hi", 14*d, 25*d, paint)
        val step = (width - 24*d) / 10
        for (row in 0..1) for (col in 0 until 10-row) {
            val x = 12*d + col*step + row*step/2
            paint.color = if (col == 9-row) palette.accent else palette.key
            rect.set(x, (33+row*18)*d, x+step-3*d, (47+row*18)*d)
            canvas.drawRoundRect(rect, 3*d, 3*d, paint)
        }
        paint.color = palette.ink; paint.textSize = 12*d
        canvas.drawText(name, 12*d, height-16*d, paint)
    }
}
