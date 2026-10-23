// 实际触摸系统键盘并验证目标输入框；运行前由脚本启用测试 IME。
package dev.qingstudy.ime

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class ImeFlowTest {
    @Test fun touchPinyinSeeEnglishAndCommitChinese() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        // Instrumentation 启动会重启目标包；之后重新选中 IME，让系统重新绑定服务。
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val editorSelector = By.res("dev.qingstudy.ime", "test_input")
        val editor = device.wait(Until.findObject(editorSelector), 10000)!!
        editor.click()
        val keyboardReady = device.wait(Until.hasObject(By.desc("全拼 · 英文释义 · 本地运行")), 30000)
        if (!keyboardReady) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "live-ui-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "live-ui-failure.xml"))
            File(context.getExternalFilesDir(null), "live-ime-state.txt").writeText(device.executeShellCommand("dumpsys input_method"))
        }
        assertTrue("系统键盘应显示就绪状态", keyboardReady)
        fun key(label: String) { device.findObject(By.desc("按键 $label")).click() }
        for (letter in "nihao") key(letter.toString())
        val helloSelector = By.desc(Pattern.compile("候选 你好，.+")).enabled(true)
        val hello = device.wait(Until.findObject(helloSelector), 15000)!!
        assertFalse(hello.contentDescription.contains("暂无英文释义"))
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "ime-candidates.png")))
        hello.click()
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text("你好")), 10000))
        key("⌫")
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text("你")), 10000))
        key("⌫")
        for (letter in "kaif") key(letter.toString())
        key("a")
        key("⌫")
        key("a")
        val develop = device.wait(Until.findObject(By.desc(Pattern.compile("候选 开发，.+")).enabled(true)), 15000)!!
        assertFalse(develop.contentDescription.contains("暂无英文释义"))
        develop.click()
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text("开发")), 10000))
        for (letter in "yingyu") key(letter.toString())
        val english = device.wait(Until.findObject(By.desc(Pattern.compile("候选 英语，.+")).enabled(true)), 15000)!!
        english.click()
        assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text("开发英语")), 10000))
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "ime-committed.png")))
        // 收起再打开，上一段拼音不得串到新会话。
        key("n")
        device.pressBack()
        device.findObject(editorSelector).click()
        assertTrue(device.wait(Until.hasObject(By.desc("全拼 · 英文释义 · 本地运行")), 10000))
    }
}
