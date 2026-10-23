// 注入 SOURCE_KEYBOARD 按下/抬起事件，走系统实体键盘分发链路。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class HardwareKeyboardTest {
    @Test fun hardwarePinyinGlossSpaceAndEditing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test",
            "dev.qingstudy.ime.EditorFixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun field(label: String) = device.wait(Until.findObject(By.desc(label)), 10000)!!
        fun press(code: Int, meta: Int = 0) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(automation.injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), action,
                    code, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
            }
        }
        fun type(text: String) { for (letter in text) when (letter) {
            in 'a'..'z' -> press(KeyEvent.KEYCODE_A + (letter - 'a'))
            in '0'..'9' -> press(KeyEvent.KEYCODE_0 + (letter - '0'))
            '+' -> press(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_SHIFT_ON)
            else -> error("unsupported test key $letter")
        } }
        fun expect(text: String) {
            // Android 无障碍节点在空文本时返回输入框的提示文字。
            val accessibleText = text.ifEmpty { "外部文本框一" }
            assertTrue("实体键盘输入应为 $text", device.wait(Until.hasObject(By.desc("外部文本框一").text(accessibleText)), 10000))
        }
        fun clear() { press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON); press(KeyEvent.KEYCODE_DEL); expect("") }
        try {
            field("外部文本框一").click()
            assertTrue(device.wait(Until.hasObject(By.desc("全拼 · 英文释义 · 本地运行")), 30000))
            type("nihao")
            val candidate = device.wait(Until.findObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000)
            assertTrue("实体键盘应生成中文和英文候选", candidate != null)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "ime-hardware-candidates.png"))
            press(KeyEvent.KEYCODE_SPACE)
            expect("你好")
            press(KeyEvent.KEYCODE_SPACE)
            expect("你好 ")
            clear()
            // 不等候选空闲就继续输入与选词，检查排队顺序。
            type("kaifa")
            press(KeyEvent.KEYCODE_DEL)
            press(KeyEvent.KEYCODE_A)
            press(KeyEvent.KEYCODE_SPACE)
            type("yingyu")
            press(KeyEvent.KEYCODE_SPACE)
            expect("开发英语")
            press(KeyEvent.KEYCODE_DEL)
            expect("开发英")
            clear()
            type("ni")
            press(KeyEvent.KEYCODE_1)
            expect("你")
            clear()
            type("nihao")
            press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            press(KeyEvent.KEYCODE_DEL)
            expect("")
            type("ni")
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_1)
            expect("n1i")
            clear()
            type("nihao")
            press(KeyEvent.KEYCODE_ENTER)
            expect("nihao\n")
            clear()
            type("nihao")
            press(KeyEvent.KEYCODE_SPACE)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "ime-hardware-committed.png"))
            clear()
            type("v1+2")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 3，.*"))), 10000))
            press(KeyEvent.KEYCODE_SPACE)
            expect("3")
            clear()
            type("u4e00")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 一，.*"))), 10000))
            press(KeyEvent.KEYCODE_SPACE)
            expect("一")
            field("外部密码框").click()
            type("nihao")
            assertEquals(5, field("外部密码框").text.length)
            assertFalse(device.hasObject(By.desc(Pattern.compile("候选 .+"))))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "hardware-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "hardware-failure.xml"))
            throw error
        }
    }
}
