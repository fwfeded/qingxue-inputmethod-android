// 合并窗口和子视图的系统安全区域；取最大值，避免重复抬高和忽略手势区域。
package dev.qingstudy.ime

import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets

internal object KeyboardInsets {
    @Suppress("DEPRECATION")
    fun resolve(dispatched: WindowInsets, root: WindowInsets?): Rect {
        val safe = Rect()
        for (insets in listOfNotNull(dispatched, root)) {
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                safe.left = maxOf(safe.left, bars.left)
                safe.right = maxOf(safe.right, bars.right)
                safe.bottom = maxOf(safe.bottom, bars.bottom,
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom)
            } else {
                safe.left = maxOf(safe.left, insets.systemWindowInsetLeft)
                safe.right = maxOf(safe.right, insets.systemWindowInsetRight)
                safe.bottom = maxOf(safe.bottom, insets.systemWindowInsetBottom)
                if (Build.VERSION.SDK_INT >= 29) {
                    safe.bottom = maxOf(safe.bottom, insets.mandatorySystemGestureInsets.bottom)
                }
            }
        }
        // 不加入 IME 自身高度，也不把左右返回手势宽度当作键盘侧边距。
        return safe
    }
}
