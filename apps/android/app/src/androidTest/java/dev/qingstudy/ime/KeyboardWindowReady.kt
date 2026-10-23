//! 等待旋转/IME入场布局停止移动后再注入一次触摸，不重试点击。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

internal fun UiDevice.waitForKeyboardGeometry() {
    waitForIdle()
    val deadline = SystemClock.uptimeMillis() + 15000
    var last = ""
    var stableSince = SystemClock.uptimeMillis()
    while (SystemClock.uptimeMillis() < deadline) {
        val keys = listOf("按键 q", "按键 空格", "按键 回车").map { name ->
            findObject(By.desc(name).enabled(true))?.visibleBounds
        }
        val current = "$displayRotation:$displayWidth:$displayHeight:$keys"
        if (current != last || keys.any { it == null || it.isEmpty }) {
            last = current; stableSince = SystemClock.uptimeMillis()
        } else if (SystemClock.uptimeMillis() - stableSince >= 700) return
        SystemClock.sleep(100)
    }
    error("旋转后键盘布局未稳定：$last")
}
