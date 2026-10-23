// 在另一个 APK 的 Activity 中验证编辑、焦点隔离、密码和键盘切换。
package dev.qingstudy.ime

import android.content.ComponentName
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    @Test fun externalEditorsPartialCommitAndSessionIsolation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test",
            "dev.qingstudy.ime.EditorFixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun field(label: String) = device.wait(Until.findObject(By.desc(label)), 10000)!!
        fun key(label: String) = device.wait(Until.findObject(By.desc("按键 $label")), 10000)!!.click()
        fun type(text: String) { for (letter in text) key(letter.toString()) }
        fun ready() { assertTrue(device.wait(Until.hasObject(By.desc("全拼 · 英文释义 · 本地运行")), 15000)) }
        fun candidate(text: String) {
            val selector = By.desc(Pattern.compile("候选 $text，.+")).enabled(true)
            var candidate = device.wait(Until.findObject(selector), 1000)
            // 窄屏/大字体时候选可能在视口之外，按真实用户操作滑动候选条。
            for (attempt in 0 until 8) {
                if (candidate != null) break
                device.findObject(By.clazz("android.widget.HorizontalScrollView").pkg("dev.qingstudy.ime"))!!
                    .scroll(Direction.RIGHT, 0.5f, 300)
                candidate = device.wait(Until.findObject(selector), 1000)
            }
            assertNotNull("滑动后应能找到候选 $text", candidate)
            candidate!!.click()
        }
        fun expect(label: String, text: String) {
            assertTrue("$label 应为 $text", device.wait(Until.hasObject(By.desc(label).text(text)), 10000))
        }
        try {
            field("外部文本框一").click()
            ready()
            type("nihaoshijie")
            candidate("你好")
            expect("外部文本框一", "你好shijie")
            candidate("世界")
            expect("外部文本框一", "你好世界")
            key("，")
            type("yingyu")
            key("空格")
            expect("外部文本框一", "你好世界，英语")
            key("回车")
            type("kaifa")
            candidate("开发")
            expect("外部文本框一", "你好世界，英语\n开发")

            // 焦点切换不能把旧框的候选或拼音插入新框。
            type("ni")
            field("外部文本框二").click()
            ready()
            type("yingyu")
            candidate("英语")
            expect("外部文本框二", "英语")
            expect("外部文本框一", "你好世界，英语\n开发ni")
            // 删除 emoji 应删除一个完整 Unicode 码点。
            field("外部文本框二").text = "A😀"
            key("⌫")
            expect("外部文本框二", "A")

            field("外部密码框").click()
            ready()
            type("nihao")
            assertFalse("密码框不应生成词典候选", device.hasObject(By.desc(Pattern.compile("候选 .+"))))
            assertEquals(5, field("外部密码框").text.length)
            key("⌫")
            assertEquals(4, field("外部密码框").text.length)
            field("外部文本框二").click()
            ready()
            type("nihao")
            candidate("你好")
            expect("外部文本框二", "A你好")

            device.wait(Until.findObject(By.desc("工具 menu")), 10000)!!.click()
            device.wait(Until.findObject(By.desc("工具 switch")), 10000)!!.click()
            assertTrue("应打开系统输入法选择器", device.wait(Until.hasObject(By.text("Android Keyboard (AOSP)")), 10000))
            device.findObject(By.text("Android Keyboard (AOSP)")).click()
            assertTrue(device.wait(Until.gone(By.desc("按键 q")), 10000))
            assertTrue(device.executeShellCommand("settings get secure default_input_method").contains("com.android.inputmethod.latin"))
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            field("外部文本框二").click()
            ready()
            type("kaifa")
            candidate("开发")
            expect("外部文本框二", "A你好开发")
            device.takeScreenshot(File(context.getExternalFilesDir(null), "ime-external-editor.png"))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "session-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "session-failure.xml"))
            throw error
        }
    }
}
