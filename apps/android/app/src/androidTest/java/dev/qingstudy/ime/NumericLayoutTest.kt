//! 真实 IME 窗口中验证参考数字列、侧栏、跨行回车与返回后的释义。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

class NumericLayoutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private lateinit var saved: JSONObject
    @Before fun prepare() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject())
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() {
        Preferences.save(context, saved)
        device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun key(label: String) = device.wait(Until.findObject(By.desc("按键 $label").enabled(true)), 15000)
        ?: error("缺少按键 $label")
    private fun start() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("试用键盘")), 10000)!!.click()
        key("q"); device.waitForIdle()
        key("123").click(); key("1"); device.waitForIdle()
    }
    private fun expect(text: String) {
        assertTrue("上屏内容应为 $text", device.wait(Until.hasObject(
            By.res(context.packageName, "test_input").text(text)), 10000))
    }
    @Test fun columnsAndTouchActionsMatchReference() {
        start()
        device.takeScreenshot(File(context.getExternalFilesDir(null), "numeric-layout.png"))
        for (column in listOf(listOf("1", "4", "7"), listOf("2", "5", "8", "0"), listOf("3", "6", "9", "."))) {
            val bounds = column.map { key(it).visibleBounds }
            assertTrue("数字列应对齐：$column $bounds",
                bounds.all { abs(it.centerX() - bounds.first().centerX()) <= 2 })
            assertTrue("数字列宽度应一致：$column", bounds.all { abs(it.width() - bounds.first().width()) <= 2 })
        }
        val enter = key("回车").visibleBounds
        val seven = key("7").visibleBounds
        val zero = key("0").visibleBounds
        assertEquals("回车从第三行开始", seven.top, enter.top)
        assertEquals("回车跨两行到底部", zero.bottom, enter.bottom)
        for (label in listOf("2", "5", "8", "0", ".", "5")) key(label).click()
        expect("2580.5")
        key("⌫").click(); expect("2580.")
        key("空格").click()
        device.wait(Until.findObject(By.desc("数字符号 %").enabled(true)), 10000)!!.click()
        expect("2580. %")
        // UiObject2 查询全部交互窗口；旧 UiSelector 只查宿主窗口，找不到 IME 的滚动列。
        val sidebar = device.wait(Until.findObject(By.desc("数字侧栏符号")), 5000)
        assertNotNull("数字侧栏应暴露可滚动节点", sidebar)
        repeat(4) {
            if (!device.hasObject(By.desc("数字符号 @"))) sidebar!!.scroll(Direction.DOWN, .9f)
        }
        assertTrue(device.wait(Until.hasObject(By.desc("数字符号 @")), 3000))
        device.findObject(By.desc("数字符号 @")).click(); expect("2580. %@")
        key("回车").click(); expect("2580. %@\n")
        key("符号").click()
        assertNotNull(device.wait(Until.findObject(By.desc("分类 常用")), 10000))
        key("ABC").click()
        // 从数字/符号返回后仍能用拼音候选及英文释义。
        for (letter in "nihao") key(letter.toString()).click()
        assertNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("候选 你好，.*hello.*"))), 15000))
        key("空格").click(); expect("2580. %@\n你好")
    }
}
