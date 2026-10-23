//! 用真实宿主重放已捕获的旧 finish 选区通知，不依赖偶发的 Binder 排队顺序。
package dev.qingstudy.ime

import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class VoiceSelectionReplayTest : VoiceGestureV4Test() {
    @Test fun delayedFinishNotificationCannotClearNewPinyin() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        val hello = By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)
        device.executeShellCommand("input text nihao")
        assertTrue(device.wait(Until.hasObject(hello),10000))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime","test_input").text("你好")),10000))
        device.executeShellCommand("input text nihao")
        assertTrue(device.wait(Until.hasObject(hello),10000))
        // 捕获时的旧事件是光标保持原位置、composing=-1；新拼音已在该位置之后。
        inst.runOnMainSync {
            editor.context.getSystemService(InputMethodManager::class.java).updateSelection(editor,2,2,-1,-1)
        }
        SystemClock.sleep(500)
        assertTrue("迟到的 finish 通知清掉了新拼音",device.hasObject(hello))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime","test_input").text("你好你好")),10000))
    }
}
