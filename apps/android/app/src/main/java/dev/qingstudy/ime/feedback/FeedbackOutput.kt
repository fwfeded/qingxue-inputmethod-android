//! 平台反馈出口，允许测试观察实际控件分发而不依赖模拟器马达或扬声器。
package dev.qingstudy.ime

import android.view.View

internal interface FeedbackOutput {
    fun prepare() = Unit
    fun haptic(view: View, followSystem: Boolean, strength: Int)
    fun sound(kind: String, volume: Int, tone: String = "system")
    fun close()
}
