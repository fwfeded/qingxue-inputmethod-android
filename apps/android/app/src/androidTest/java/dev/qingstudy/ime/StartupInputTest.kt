// 真实 IME 进程冷启动，在加载提示仍可见时输入；不得把拼音漏给编辑器。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupInputTest {
    @Test fun hardwareBurstDuringLoadingCommitsChinese() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val automation = instrumentation.uiAutomation
        val saved = Preferences.read(context)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        fun loading(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.contentDescription?.toString() == "正在加载本地词库…") return true
            return (0 until node.childCount).any { loading(node.getChild(it)) }
        }
        fun press(code: Int) {
            val start = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(automation.injectInputEvent(KeyEvent(start, SystemClock.uptimeMillis(), action,
                    code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
            }
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode text -f 0x10008000")
            device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
            // 只终止独立 IME 进程，保留测试宿主和编辑器。下一次选择会重新加载真实词库。
            val oldPid = device.executeShellCommand("pidof dev.qingstudy.ime:ime").trim()
            if (oldPid.isNotEmpty()) {
                require(oldPid.all { it.isDigit() })
                device.executeShellCommand("run-as dev.qingstudy.ime kill -9 $oldPid")
            }
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            val deadline = SystemClock.uptimeMillis() + 10000
            var sawLoading = false
            while (!sawLoading && SystemClock.uptimeMillis() < deadline) {
                sawLoading = automation.windows.any { loading(it.root) }
                if (!sawLoading) SystemClock.sleep(10)
            }
            assertTrue("必须实际命中加载期，不能等待就绪后再冒充冷启动输入", sawLoading)
            for (letter in "nihao") press(KeyEvent.KEYCODE_A + (letter - 'a'))
            press(KeyEvent.KEYCODE_SPACE)
            assertTrue("加载期拼音与空格应排队后上屏你好", device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 30000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "startup-queued-commit.png"))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "startup-queued-failure.png"))
            throw error
        } finally { Preferences.save(context, saved) }
    }
}
