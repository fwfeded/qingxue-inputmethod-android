//! 设置页预览与真实键盘共用的反馈门控；语音开启时停止按键声，配置不在逐键路径读盘。
package dev.qingstudy.ime

import android.content.Context
import android.view.View

internal class KeyboardFeedback(context: Context, private val output: FeedbackOutput = AndroidFeedbackOutput(context)) {
    var settings = FeedbackSettings.read(context)
        set(value) { field = value; if (value.sound) output.prepare() else output.close() }
    init { if (settings.sound) output.prepare() }
    fun prepare() { if (settings.sound) output.prepare() }
    var suppressed = false
    var recording = false
    fun press(view: View, kind: String = "letter", sound: Boolean = true) {
        if (suppressed || !view.isEnabled) return
        if (settings.vibrate) output.haptic(view, settings.followSystem, settings.strength)
        if (sound && !recording && settings.sound && settings.volume > 0) output.sound(kind, settings.volume, settings.tone)
    }
    fun bind(view: View) {
        view.isSoundEffectsEnabled = false
        view.isHapticFeedbackEnabled = false
        if (view is KeyboardKey) view.onFeedback = { press(view, view.feedbackKind) }
        else if (view.isClickable) view.setOnTouchListener { target, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) press(target, "action")
            false
        }
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) bind(view.getChildAt(index))
    }
    fun close() = output.close()
}
