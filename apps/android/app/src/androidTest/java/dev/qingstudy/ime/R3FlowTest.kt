// R3 真实窗口回归：首页状态、直接入口、连续符号、完整表情删除及高度稳定。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class R3FlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var saved: JSONObject
    private val ime = "dev.qingstudy.ime/.PrototypeIme"
    @Before fun prepare() {
        saved = Preferences.read(context); Preferences.save(context, JSONObject())
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime enable $ime")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set $ime")
    }
    @After fun restore() {
        Preferences.save(context, saved)
        device.executeShellCommand("ime enable $ime"); device.executeShellCommand("ime set $ime")
        device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun launchHome() {
        device.pressHome()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("青学")), 10000))
    }
    private fun click(desc: String) { device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000)!!.click() }
    private fun type(text: String) { text.forEach { click("按键 $it") } }
    private fun expect(text: String) { assertTrue("试打内容应为 $text", device.wait(Until.hasObject(By.res(context.packageName, "test_input").text(text)), 15000)) }
    private fun shot(name: String) { device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "r3-$name.png")) }
    private fun keyboardTop() = device.findObject(By.desc("工具 menu")).visibleBounds.top

    @Test fun homeReflectsSystemStateAndOpensRealSettings() {
        launchHome()
        assertTrue(device.wait(Until.hasObject(By.text("可以开始打字了")), 10000))
        shot("home-ready")
        click("键盘外观")
        device.wait(Until.findObject(By.text("尺寸与文字")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("按屏宽自动调整高度")), 10000))
        device.pressBack()
        click("管理词库")
        assertTrue(device.wait(Until.hasObject(By.text("词库导入与导出")), 10000))
        launchHome(); click("学习设置")
        assertTrue(device.wait(Until.hasObject(By.text("本机学习、词汇记录与统计")), 10000))
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        launchHome()
        assertTrue(device.wait(Until.hasObject(By.text("选择青学")), 10000))
        shot("home-unselected")
        device.findObject(By.text("选择青学")).click()
        device.wait(Until.findObject(By.text("青学输入法")), 10000)!!.click()
        assertTrue("关闭系统选择器后首页应立即更新", device.wait(Until.hasObject(By.text("可以开始打字了")), 10000))
        shot("home-after-picker")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime disable $ime")
        launchHome()
        assertTrue(device.wait(Until.hasObject(By.text("去启用")), 10000))
        shot("home-disabled")
    }

    @Test fun keyboardCandidatesSymbolsEmojiNumbersAndPunctuation() {
        launchHome()
        device.wait(Until.findObject(By.text("试用键盘")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q").enabled(true)), 30000))
        val top = keyboardTop()
        shot("keyboard-idle")
        type("nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        assertEquals("候选和空闲工具区不能跳高", top, keyboardTop())
        shot("keyboard-candidates")
        click("按键 符号")
        expect("你好")
        assertTrue(device.wait(Until.hasObject(By.desc("分类 常用")), 10000))
        assertEquals(top, keyboardTop())
        val symbol = device.wait(Until.findObject(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)), 10000)!!
        val value = symbol.text
        val symbolDescription = symbol.contentDescription
        symbol.click(); expect("你好$value")
        device.wait(Until.findObject(By.desc(symbolDescription).enabled(true)), 10000)!!.click()
        expect("你好$value$value")
        shot("symbols")
        click("按键 ⌫"); expect("你好$value")
        click("按键 ABC")
        click("工具 emoji")
        val emoji = device.wait(Until.findObject(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)), 10000)!!
        val face = emoji.text
        emoji.click(); expect("你好$value$face")
        shot("emoji")
        click("按键 ⌫"); expect("你好$value")
        click("按键 ABC")
        type("nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*")).enabled(true)), 15000))
        device.findObject(By.desc("按键 ，")).longClick()
        device.wait(Until.findObject(By.text("？")), 10000)!!.click()
        expect("你好${value}你好？")
        click("按键 123"); click("按键 1"); click("按键 2")
        expect("你好${value}你好？12")
        shot("numbers")
        click("工具 symbols")
        // 数字区也有 ABC，先等符号分类出现，避免点到正在被替换的数字键。
        assertTrue(device.wait(Until.hasObject(By.desc("分类 常用")), 10000))
        device.waitForIdle()
        click("按键 ABC")
        assertTrue("数字进入符号后 ABC 必须回到字母", device.wait(Until.hasObject(By.desc("按键 q")), 10000))
        assertEquals(top, keyboardTop())
        click("按键 回车")
        expect("你好${value}你好？12\n")
        click("工具 emoji")
        device.wait(Until.findObject(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)), 10000)
        device.waitForIdle()
        val grid = device.findObjects(By.clazz("android.widget.ScrollView")).last { it.visibleBounds.top > keyboardTop() }.visibleBounds
        device.swipe(grid.centerX(), grid.bottom - 12, grid.centerX(), grid.top + 12, 20)
        device.waitForIdle()
        val scrolled = device.findObjects(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)).last {
            val bounds = it.visibleBounds
            !bounds.isEmpty && bounds.top >= grid.top && bounds.bottom <= grid.bottom
        }
        val description = scrolled.contentDescription
        val glyph = scrolled.text
        val position = scrolled.visibleBounds.top
        scrolled.click()
        expect("你好${value}你好？12\n$glyph")
        val same = device.wait(Until.findObject(By.desc(description).enabled(true)), 10000)
        assertNotNull("连续选表情后当前条目应仍可见", same)
        assertEquals("连续选表情应保留滚动位置", position, same!!.visibleBounds.top)
        shot("emoji-scrolled")
    }
}
