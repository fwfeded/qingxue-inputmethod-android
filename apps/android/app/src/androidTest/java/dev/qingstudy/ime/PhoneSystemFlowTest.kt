// 实际 IME 窗口回归：收起与重开、原文保留、末行符号/表情和横屏点击。
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
class PhoneSystemFlowTest {
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
        device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun click(desc: String) { assertNotNull(device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000).also { it?.click() }) }
    private fun expect(text: String) {
        val matched = device.wait(Until.hasObject(By.res(context.packageName, "test_input").text(text)), 15000)
        if (!matched) {
            shot("failure")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "phone-ui-failure.xml"))
        }
        assertTrue("输入内容应为 $text，实际为 ${device.findObject(By.res(context.packageName, "test_input"))?.text}", matched)
    }
    private fun shot(name: String) { device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "phone-ui-$name.png")) }
    private fun reopen() {
        device.wait(Until.findObject(By.res(context.packageName, "test_input")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q").enabled(true)), 30000))
        // 点击输入框会把光标移到点击位置；继续追加/删末字前明确定位行尾。
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)
    }
    private fun hide() {
        click("收起键盘")
        assertTrue("点击收起必须关闭 IME 窗口", device.wait(Until.gone(By.desc("收起键盘")), 10000))
    }
    private fun panelItem() = device.wait(Until.findObject(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)), 15000)!!
    private fun bottomTap(desc: String) {
        val bounds = device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000)!!.visibleBounds
        assertTrue("末行按钮应有完整触摸高度", bounds.height() >= 30)
        device.click(bounds.centerX(), bounds.bottom - 6)
    }

    @Test fun hideReopenAndBottomRowStayUsable() {
        device.pressHome()
        context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        reopen()
        // 无障碍可能把 EditText 的提示词作为 text 返回；收起前后应保持一致。
        val initiallyDisplayed = device.findObject(By.res(context.packageName, "test_input")).text.orEmpty()
        shot("idle"); hide(); expect(initiallyDisplayed)
        reopen()
        "nihao".forEach { click("按键 $it") }
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        shot("candidates"); hide(); expect("nihao")
        reopen()
        "nihao".forEach { click("按键 $it") }
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        click("按键 空格"); expect("nihao你好")
        bottomTap("按键 符号")
        val symbol = panelItem().text; panelItem().click(); expect("nihao你好$symbol")
        shot("symbols"); hide(); reopen()
        click("工具 emoji")
        val emoji = panelItem().text; panelItem().click(); expect("nihao你好$symbol$emoji")
        shot("emoji"); hide(); reopen()
        click("按键 ⌫"); expect("nihao你好$symbol")
        device.setOrientationLeft()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q").enabled(true)), 15000))
        device.waitForIdle()
        bottomTap("按键 符号")
        assertNotNull(panelItem()); shot("landscape-symbols"); hide()
    }
}
