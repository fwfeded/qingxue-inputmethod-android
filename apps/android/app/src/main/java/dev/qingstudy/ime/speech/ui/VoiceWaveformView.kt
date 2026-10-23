//! 只绘制真实音量回调的短历史；静音或结束清空，不用时钟生成假波形。
package dev.qingstudy.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class VoiceWaveformView(context: Context, color: Int) : View(context) {
    private val samples = FloatArray(20)
    private val shown = FloatArray(20)
    private var live = false
    private var lastFrame = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }

    init { contentDescription = "实时语音音量"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun level(value: Float) {
        val bounded = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (bounded == 0f) { samples.fill(0f); shown.fill(0f) }
        else { samples.copyInto(samples, 0, 1); samples[samples.lastIndex] = bounded; if(!live) samples.copyInto(shown) }
        invalidate()
    }

    fun active(value: Boolean) { live=value;lastFrame=0L;if(!value)level(0f) else postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now=android.os.SystemClock.uptimeMillis()
        val dt=if(lastFrame==0L)16f else (now-lastFrame).coerceIn(1,64).toFloat()
        lastFrame=now
        val blend=1f-kotlin.math.exp(-dt/65f)
        shown.indices.forEach { shown[it]+=(samples[it]-shown[it])*blend }
        val step = width.toFloat() / shown.size
        val baseline = resources.displayMetrics.density
        paint.strokeWidth = minOf(step * .48f, baseline * 2.5f)
        shown.forEachIndexed { index, sample ->
            val halfHeight = baseline + sample * (height / 2f - baseline).coerceAtLeast(0f)
            val x = (index + .5f) * step
            canvas.drawLine(x, height / 2f - halfHeight, x, height / 2f + halfHeight, paint)
        }
        if(live&&isAttachedToWindow&&isShown)postInvalidateOnAnimation()
    }
    override fun onDetachedFromWindow() { active(false);super.onDetachedFromWindow() }
}
