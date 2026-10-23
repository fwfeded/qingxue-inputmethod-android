//! 真实 IME 的主题、反馈开关与固定预览回归；保留并恢复用户配置。
package dev.qingstudy.ime

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.regex.Pattern

class FeedbackThemeFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private lateinit var saved: JSONObject
    @Before fun setup() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject().put("mixed", false))
        device.setOrientationNatural()
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    @After fun restore() {
        Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun openSettings(tab: String = "主题") {
        // 同名页面重开时先等新 Activity 完成启动，避免误取旧窗口的标签。
        inst.startActivitySync(Intent(context, AppearanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync(); device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("键盘与外观")), 10000))
        device.wait(Until.findObject(By.text(tab)), 5000)!!.click(); device.waitForIdle()
    }
    private fun click(desc: String) { device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000)!!.click(); device.waitForIdle() }
    private fun scrollText(text: String) {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text)); device.waitForIdle()
    }
    private fun shot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-$name.png")) }
    private fun stableDescription(description: String): android.graphics.Rect {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(description))
        return stableControl(By.desc(description))
    }
    private fun stableControl(selector: androidx.test.uiautomator.BySelector): android.graphics.Rect {
        var previous = android.graphics.Rect()
        var stable = 0
        repeat(40) {
            val bounds = device.findObject(selector)?.visibleBounds ?: android.graphics.Rect()
            stable = if (!bounds.isEmpty && bounds == previous) stable + 1 else 0
            if (stable >= 5) return bounds
            previous = bounds
            android.os.SystemClock.sleep(100)
        }
        error("控件布局未稳定：$selector")
    }
    private fun openEditor() {
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
            .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.waitForKeyboardGeometry()
    }
    @Test fun sixThemesSwitchAndKeepGlossNumericAndSymbols() {
        for ((id, name) in KeyboardThemes.names) {
            openSettings()
            // 滚动后的首次触摸可能用于停止惯性；等卡片几何稳定后只点一次。
            stableDescription("主题 $name")
            click("主题 $name")
            assertEquals(id, KeyboardAppearance.read(context).theme)
            shot("theme-$id-settings")
            openEditor()
            for (letter in "nihao") click("按键 $letter")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*"))), 10000))
            shot("theme-$id-keyboard")
            click("按键 空格")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 5000))
            click("按键 123"); click("按键 1")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好1")), 5000))
            shot("theme-$id-number")
            click("按键 符号")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("符号 .*"))), 10000))
            shot("theme-$id-symbol")
        }
        assertFalse(Preferences.read(context).getBoolean("mixed"))
    }
    @Test fun feedbackDependenciesAndValuesPersistAcrossReopening() {
        openSettings("按键反馈")
        assertFalse(device.findObject(By.text("按键震动")).isChecked)
        assertFalse(device.findObject(By.text("震动跟随系统")).isEnabled)
        device.findObject(By.text("按键震动")).click(); device.waitForIdle()
        assertTrue(device.findObject(By.text("震动跟随系统")).isEnabled)
        stableDescription("震动强度")
        assertFalse(device.findObject(By.desc("震动强度")).isEnabled)
        scrollText("震动跟随系统"); stableControl(By.text("震动跟随系统"))
        device.findObject(By.text("震动跟随系统")).click(); device.waitForIdle()
        stableDescription("震动强度")
        click("震动强度"); device.wait(Until.findObject(By.text("明显")), 5000)!!.click(); device.waitForIdle()
        scrollText("按键音效"); device.findObject(By.text("按键音效")).click(); device.waitForIdle()
        scrollText("恢复默认反馈")
        val bounds = stableDescription("按键音量")
        val slider = device.findObject(By.desc("按键音量"))
        assertTrue(slider.isEnabled)
        shot("volume-before")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "feedback-volume-before.xml"))
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.right-1, bounds.centerY(), 20); device.waitForIdle()
        val result = FeedbackSettings.read(context)
        assertTrue(result.vibrate); assertFalse(result.followSystem); assertEquals(3, result.strength)
        assertTrue(result.sound); assertEquals(100, result.volume)
        for (kind in listOf("letter", "delete", "action")) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description("反馈试按 $kind"))
            click("反馈试按 $kind")
        }
        shot("feedback-controls")
        openSettings("按键反馈")
        assertEquals(result, FeedbackSettings.read(context))
        scrollText("恢复默认反馈")
        // 滚动尚未结束时，第一下触摸会被 ScrollView 用于停止惯性，不是按钮点击。
        stableControl(By.text("恢复默认反馈"))
        device.findObject(By.text("恢复默认反馈")).click(); device.waitForIdle()
        // 只等待这一次点击保存，不通过重复点击掩盖失败。
        val resetDeadline = android.os.SystemClock.uptimeMillis() + 3000
        while (FeedbackSettings.read(context) != FeedbackSettings() && android.os.SystemClock.uptimeMillis() < resetDeadline) android.os.SystemClock.sleep(25)
        shot("feedback-after-reset")
        assertEquals(FeedbackSettings(), FeedbackSettings.read(context))
        assertFalse(Preferences.read(context).getBoolean("mixed"))
    }
    @Test fun bottomSlidersStayLiveAndTrialReturnsToSameScrollPosition() {
        openSettings("尺寸与文字")
        val selector = By.desc("候选 你好，hello; hi")
        val top = device.findObject(selector).visibleBounds.top
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description("英文释义字号")); device.waitForIdle()
        val bounds = device.findObject(By.desc("英文释义字号")).visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.right-1, bounds.centerY(), 20); device.waitForIdle()
        assertEquals(18, KeyboardAppearance.read(context).glossSize)
        assertEquals(top, device.findObject(selector).visibleBounds.top)
        val before = device.findObject(By.desc("英文释义字号")).visibleBounds
        shot("size-bottom-live")
        click("完整试打")
        assertTrue(device.wait(Until.hasObject(By.text("试用键盘")), 5000))
        if (device.wait(Until.hasObject(By.desc("收起键盘")), 10000)) click("收起键盘")
        click("返回上一级")
        assertTrue(device.wait(Until.hasObject(By.text("键盘与外观")), 5000))
        assertEquals(before, device.findObject(By.desc("英文释义字号")).visibleBounds)
        device.setOrientationLeft(); device.waitForIdle()
        val present = device.wait(Until.hasObject(selector), 10000)
        shot("landscape-before-scroll")
        assertTrue("横屏预览应在旋转后可见", present)
        scrollText("恢复默认外观"); assertTrue(device.hasObject(selector))
        shot("landscape-fixed-preview")
    }
}
