// 手机窄屏、横竖屏和专用输入框的实际系统键盘验证。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class PhoneCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var device: UiDevice

    @Before fun prepare() {
        device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() { device.setOrientationNatural(); device.unfreezeRotation() }
    private fun open(mode: String = "text") {
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test",
            "dev.qingstudy.ime.PhoneFixtureActivity")).putExtra("mode", mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 $mode")), 10000))
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
    }
    private fun key(label: String) {
        val button = device.wait(Until.findObject(By.desc("按键 $label").enabled(true)), 10000)
        assertTrue("手机触屏应有可用的 $label 键", button != null)
        button!!.click()
    }
    private fun type(text: String) { for (character in text) key(character.toString()) }
    private fun expect(text: String) {
        assertTrue("输入应为 $text", device.wait(Until.hasObject(By.desc("手机测试输入框").text(text)), 10000))
    }
    private fun snapshot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")) }
    private fun checkLayout() {
        val field = device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!
        val topKey = device.wait(Until.findObject(By.desc("按键 q")), 10000)
        assertTrue("屏幕需要显示字母键", topKey != null)
        assertTrue("横屏输入框应保留可见编辑区域", field.visibleBounds.height() >= 64)
        assertTrue("输入框不应被键盘遮住", field.visibleBounds.bottom <= topKey!!.visibleBounds.top)
        // 字母键允许在加载时排队，enabled 不能用作词库加载完成的信号。
        val readyStatus = device.wait(Until.findObject(By.desc("全拼 · 英文释义 · 本地运行")), 30000)
        assertTrue("全拼词库应加载完成", readyStatus != null)
        val keyboardTop = readyStatus!!.visibleBounds.top
        snapshot(if (device.displayWidth > device.displayHeight) "phone-landscape" else "phone-portrait")
        assertTrue("键盘应留出至少四分之一屏幕给应用", keyboardTop > device.displayHeight / 4)
        for (label in listOf("q", "p", "⌫", "空格", "回车")) {
            val bounds = device.findObject(By.desc("按键 $label"))!!.visibleBounds
            assertTrue("$label 应有完整触摸区域", bounds.width() >= 32 && bounds.height() >= 40)
            assertTrue(bounds.left >= 0 && bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
        }
    }
    @Test fun touchPhonePortraitLandscapeAndSearch() {
        open("search")
        checkLayout()
        type("nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        snapshot("phone-portrait-candidates")
        key("空格")
        expect("你好")
        device.setOrientationLeft()
        device.waitForKeyboardGeometry()
        checkLayout()
        type("yingyu")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 英语，.*English.*")).enabled(true)), 15000))
        snapshot("phone-landscape-candidates")
        key("空格")
        expect("你好英语")
        key("回车")
        // 大字体横屏时结果标签在键盘后方，收起键盘再检查编辑器动作回调。
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("已触发动作 3")), 10000))
        device.setOrientationNatural()
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        device.waitForKeyboardGeometry()
        type("kaifa")
        key("空格")
        expect("你好英语开发")
    }
    @Test fun numbersPhonePasswordAndReturnToPinyin() {
        open("number")
        type("123456")
        expect("123456")
        key("⌫")
        expect("12345")
        snapshot("phone-number")
        open("phone")
        type("13800138000")
        expect("13800138000")
        open("password")
        type("abc")
        key("123")
        type("123")
        assertEquals(6, device.findObject(By.desc("手机测试输入框")).text.length)
        assertTrue(device.findObjects(By.desc(Pattern.compile("候选 .+"))).isEmpty())
        open()
        type("nihao")
        key("空格")
        expect("你好")
        key("123")
        type("2026")
        expect("你好2026")
        key("返回")
        type("yingyu")
        key("空格")
        expect("你好2026英语")
    }
}
