// 在真实输入法窗口验证 R4 的底行、副符号、留白及连续删除。
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
class R4FlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var saved: JSONObject
    @Before fun prepare() {
        saved = Preferences.read(context); Preferences.save(context, JSONObject())
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() { Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation() }
    private fun start() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("试用键盘")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q").enabled(true)), 30000))
        device.waitForIdle()
    }
    private fun key(desc: String) = device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000)!!
    private fun click(desc: String) = key(desc).click()
    private fun type(text: String) = text.forEach { click("按键 $it") }
    private fun expect(text: String) { assertTrue("试打内容应为 $text", device.wait(Until.hasObject(By.res(context.packageName, "test_input").text(text)), 15000)) }
    private fun shot(name: String) { device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "r4-$name.png")) }
    private fun swipeKey(letter: String) {
        val r = key("按键 $letter").visibleBounds
        device.swipe(r.centerX(), r.centerY(), r.centerX(), r.top - r.height() / 4, 10)
    }
    @Test fun bottomRowHasSeparateNumbersAndSymbols() {
        start()
        val symbol = key("按键 符号").visibleBounds
        val numbers = device.findObjects(By.desc("按键 123")).map { it.visibleBounds }
        shot("idle")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "r4-idle.xml"))
        assertTrue("123 应直接位于符号旁的底行：symbol=$symbol numbers=$numbers", numbers.any { it.top == symbol.top && it.left >= symbol.right })
        click("按键 符号"); assertNotNull(key("分类 常用"))
        shot("symbols"); click("按键 ABC")
        click("工具 emoji")
        assertNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("符号 .+")).enabled(true)), 10000))
        shot("emoji")
    }
    @Test fun auxiliarySwipeLongPressAndLearningGlossRemainUsable() {
        start(); type("nihao")
        assertNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        shot("candidates")
        swipeKey("q"); expect("你好1")
        key("按键 w").longClick()
        device.wait(Until.findObject(By.text("2")), 10000)!!.click(); expect("你好12")
        click("按键 a"); expect("你好12a")
        click("按键 ⌫"); expect("你好12")
        type("nihao"); key("工具 details").click()
        assertNotNull(device.wait(Until.findObject(By.text("完整释义")), 10000))
        val translation = device.wait(Until.findObject(By.desc(Pattern.compile("输入译词：.*hello.*")).enabled(true)), 10000)!!
        val translated = translation.contentDescription.substringAfter("输入译词：")
        translation.click(); expect("你好12$translated")
    }
    @Test fun heldBackspaceStopsOnReleaseAndKeepsRemainingText() {
        start()
        val value = "123456789012345678901234567890"
        device.findObject(By.res(context.packageName, "test_input")).text = value
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)
        val bounds = key("按键 ⌫").visibleBounds
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int) {
            android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
        event(android.view.MotionEvent.ACTION_DOWN)
        try { android.os.SystemClock.sleep(1000) } finally { event(android.view.MotionEvent.ACTION_UP) }
        device.waitForIdle()
        val after = device.findObject(By.res(context.packageName, "test_input")).text
        assertTrue("长按应逐字删除而不是清空：$after", after.length in 1..value.length - 3)
        android.os.SystemClock.sleep(600)
        expect(after)
        shot("repeat-delete")
    }

    @Test fun bottomSpaceAndMergedLayoutWorkInRealWindow() {
        Preferences.save(context, JSONObject().put("appearance", JSONObject().put("autoHeight", true)
            .put("bottomSpace", 24).put("splitBottom", false).put("numberRow", true)))
        start()
        val bottom = key("按键 ?123").visibleBounds
        val height = context.resources.displayMetrics.density * 24
        assertTrue("底行下方应留出额外空间和导航区", device.displayHeight - bottom.bottom >= height)
        val top = key("工具 menu").visibleBounds.top
        click("按键 1"); expect("1")
        click("按键 ?123"); assertNotNull(key("分类 常用"))
        assertEquals("开启数字行后面板切换也应等高", top, key("工具 menu").visibleBounds.top)
        shot("extra-bottom-symbols")
        click("按键 ABC"); click("按键 ☺")
        assertNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("符号 .+")).enabled(true)), 10000))
        assertEquals(top, key("工具 menu").visibleBounds.top)
        click("收起键盘")
        assertTrue(device.wait(Until.gone(By.desc("收起键盘")), 10000))
    }
}
