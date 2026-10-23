//! V4 长按画布：参考几何与200ms状态变化；不把音量或识别状态伪造成时钟动画。
package dev.qingstudy.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.atan2

internal class VoiceHoldView(context: Context, private val action: (String, String) -> Unit) : FrameLayout(context) {
    private val palette = KeyboardPalette(context, KeyboardAppearance.read(context))
    private val dark = palette.dark
    var feedback: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val measure = PathMeasure()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private val box = RectF()
    private val wave = VoiceWaveformView(context, palette.muted)
    private val status = TextView(context)
    private val left = accessible("cancel", "撤回输入")
    private val right = accessible("send", "发送")
    private val centre = accessible("finish", "松手结束")
    private var label = "换行"
    private var target = "finish"
    private var progress = 1f
    private var centreOffset = 0f
    private var animator: ValueAnimator? = null
    private var glow: Shader? = null
    private val scale: Float get() = minOf(width / 360f, height / 245f)
    var zoneChanged: ((Boolean) -> Unit)? = null

    init {
        setWillNotDraw(false)
        addView(wave)
        status.apply { contentDescription = "语音状态"; gravity = Gravity.CENTER; textSize = 11f; setTextColor(palette.muted) }
        addView(status)
        addView(left); addView(right); addView(centre)
    }
    private fun accessible(command: String, name: String) = TextView(context).apply {
        contentDescription = "语音 $command"; isFocusable = true
        setOnClickListener { action(command, "") }
        // 标签由画布沿曲线绘制；独立节点保留 TalkBack 和键盘可访问操作。
        tooltipText = name
    }
    fun mode(actionName: String) { label = actionName; right.tooltipText = actionName; target = "finish"; centreOffset = 0f; progress = 1f; invalidate() }
    fun level(value: Float) { wave.level(value) }
    fun update(message: String, running: Boolean, processing: Boolean) {
        wave.active(running && !processing)
        status.text = if (!running || message.contains("载入") || message.contains("准备") || message.contains("连接")) message else ""
        status.contentDescription = "语音状态"
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow = RadialGradient(w*.5f, h*.42f, maxOf(w,h)*.72f,
            intArrayOf(palette.function, palette.background),null,Shader.TileMode.CLAMP)
    }
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w,h)
        val s=minOf(measuredWidth/360f,measuredHeight/245f)
        fun size(view: android.view.View, width: Float, height: Float) { view.measure(MeasureSpec.makeMeasureSpec(width.toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height.toInt(),MeasureSpec.EXACTLY)) }
        size(wave,120*s,40*s); size(status,measuredWidth.toFloat(),28*s)
        size(left,measuredWidth*.46f,60*s);size(right,measuredWidth*.46f,60*s);size(centre,measuredWidth*.54f,70*s)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s=scale
        fun at(v: android.view.View,x: Float,y: Float) { v.layout(x.toInt(),y.toInt(),x.toInt()+v.measuredWidth,y.toInt()+v.measuredHeight) }
        at(wave,(width-wave.measuredWidth)/2f,(height-161*s)/2-wave.measuredHeight/2)
        at(status,0f,10*s)
        at(left,0f,height-160*s);at(right,width-right.measuredWidth.toFloat(),height-160*s)
        at(centre,(width-centre.measuredWidth)/2f,height-70*s)
    }
    fun hover(x: Float, y: Float): String {
        val s=scale
        if(s<=0f)return "finish"
        val top=height-88*s
        val cy=top+(centreOffset*s+260*s)/2
        val rx=493*s/2;val ry=(260-centreOffset)*s/2
        val dx=(x-width/2f)/rx;val dy=(y-cy)/ry
        val next=when { dx*dx+dy*dy<=1 -> "finish"; x>=0&&x<width/2f -> "cancel"; x>width/2f&&x<=width -> "send"; else -> "finish" }
        if(next!=target) {
            target=next;animator?.cancel()
            val from=centreOffset;val to=if(next=="finish")0f else 5f
            progress=0f
            animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=200
                addUpdateListener { progress=it.animatedValue as Float;centreOffset=from+(to-from)*progress;invalidate() }
                start()
            }
            feedback?.invoke()
            zoneChanged?.invoke(next=="finish")
        }
        return next
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style=Paint.Style.FILL;paint.shader=glow;canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.shader=null
        val s=scale
        if(s<=0f)return
        for(isLeft in listOf(true,false)) {
            val selected=target==if(isLeft)"cancel" else "send"
            arc(isLeft,if(selected)progress else 0f,50f)
            paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=(57+if(selected)progress*8 else 0f)*s
            paint.color=if(selected)palette.key else (palette.ink and 0xFFFFFF) or 0x0F000000
            canvas.drawPath(path,paint)
            val word=if(isLeft)"撤回输入" else label
            val color=if(selected && isLeft) { if(dark)0xFFFF9999.toInt() else 0xFFC62828.toInt() } else palette.ink
            val anchor=textOnArc(canvas,word,isLeft,if(selected)17f else 15f,color,null)
            if(selected) {
                val hint=if(isLeft)"松手 撤回" else "松手 一键$label"
                arc(isLeft,progress,0f)
                textOnArc(canvas,hint,isLeft,11f,if(isLeft)color else palette.muted,anchor)
            }
        }
        paint.style=Paint.Style.FILL;paint.color=(palette.key and 0xFFFFFF) or if(target=="finish")0x4D000000 else 0x26000000
        val top=height-88*s
        box.set((width-493*s)/2,top+centreOffset*s,(width+493*s)/2,top+260*s)
        canvas.drawOval(box,paint)
        paint.textSize=11*s;paint.textAlign=Paint.Align.CENTER;paint.color=palette.muted
        canvas.drawText("松手结束",width/2f,top+31*s,paint);paint.textAlign=Paint.Align.LEFT
    }
    private fun arc(left: Boolean,p: Float,offset: Float) {
        val s=scale;val stroke=(57+8*p)*s
        val rx=(698*s+stroke)/2;val ry=(465*s+stroke)/2
        val cy=height-211*s+57*s/2+ry+offset*s
        box.set(width/2f-rx,cy-ry,width/2f+rx,cy+ry)
        path.reset();path.addArc(box,if(left)-97f else -83f,if(left)-180f else 180f)
        measure.setPath(path,false)
    }
    private fun textOnArc(canvas: Canvas,text: String,left: Boolean,size: Float,color: Int,anchor: Float?): Float {
        paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.LEFT;paint.textSize=size*scale;paint.color=color
        val widthText=paint.measureText(text)
        var distance=anchor ?: (((width-50*scale)/2-widthText)/2-if(left)0f else widthText/2)
        if(anchor!=null&&!left)distance-=kotlin.math.abs(widthText-paint.measureText(label))/2
        var mark=distance
        val chars=if(left)text.reversed() else text
        chars.forEachIndexed { index,ch ->
            if(!measure.getPosTan(distance,position,tangent))return@forEachIndexed
            val glyph=ch.toString();val advance=paint.measureText(glyph)
            canvas.save();canvas.translate(position[0],position[1]+(paint.fontMetrics.bottom-paint.fontMetrics.top)/4)
            canvas.rotate(Math.toDegrees(atan2(tangent[1],tangent[0]).toDouble()).toFloat()-if(left)180f else 0f)
            canvas.drawText(glyph,0f,0f,paint);canvas.restore()
            if(left&&index==chars.length/2-1)mark=distance
            if(!left&&index==0)mark=distance+advance/2
            distance+=advance
        }
        return mark
    }
    override fun onDetachedFromWindow() { animator?.cancel();wave.active(false);super.onDetachedFromWindow() }
}
