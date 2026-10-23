// 与 R4 预览一致的书本线条，避免不同手机的 emoji 字体替换学习图标。
package dev.qingstudy.ime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class BookIcon(color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val path = Path().apply {
            moveTo(12f, 5f); quadTo(7f, 2f, 3f, 4f); lineTo(3f, 20f); quadTo(8f, 18f, 12f, 21f)
            quadTo(16f, 18f, 21f, 20f); lineTo(21f, 4f); quadTo(17f, 2f, 12f, 5f); lineTo(12f, 21f)
        }
        canvas.drawPath(path, paint); canvas.restore()
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
