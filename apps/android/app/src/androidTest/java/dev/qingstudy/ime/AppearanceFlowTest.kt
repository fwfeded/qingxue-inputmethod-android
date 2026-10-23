// 外观设置、跨进程生效与展开候选的实际手机输入流程。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class AppearanceFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var device: UiDevice
    private lateinit var saved: JSONObject
    @Before fun prepare() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject().put("mixed", false))
        device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() {
        Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun openEditor() {
        device.pressHome(); device.waitForIdle()
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
            .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
    }
    private fun click(desc: String) { device.wait(Until.findObject(By.desc(desc).enabled(true)), 10000)!!.click() }
    private fun type(value: String) { value.forEach { click("按键 $it") } }
    private fun shot(name: String) { device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")) }
    private fun openAppearance() {
        instrumentation.startActivitySync(Intent(context, AppearanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        assertTrue(device.wait(Until.hasObject(By.text("键盘与外观")), 10000)); device.waitForIdle()
        device.findObject(By.text("尺寸与文字")).click(); device.waitForIdle()
    }
    private fun maxSlider(title: String) {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(title))
        device.waitForIdle()
        android.os.SystemClock.sleep(700)
        val bounds = device.wait(Until.findObject(By.desc(title)), 10000)!!.visibleBounds
        if (title == "中文候选字号") {
            shot("appearance-slider-before")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "appearance-slider-before.xml"))
        }
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.right - 1, bounds.centerY(), 20)
        val expected = when (title) {
            "竖屏按键行高" -> "66 dp"
            "额外底部留白" -> "36 dp"
            "按键间距" -> "7 dp"
            "按键圆角" -> "12 dp"
            "英文释义字号" -> "18 sp"
            else -> "30 sp"
        }
        val changed = device.wait(Until.hasObject(By.text("$title：$expected")), 10000)
        if (!changed) {
            shot("appearance-slider-failure")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "appearance-slider-failure.xml"))
            File(context.getExternalFilesDir(null), "appearance-slider-failure.json").writeText(
                JSONObject().put("bounds", bounds.toShortString()).put("settings", Preferences.read(context)).toString())
        }
        assertTrue("滑块应完成数值更新：$title，实际 ${KeyboardAppearance.read(context)}", changed)
        device.waitForIdle()
    }
    @Test fun settingsPersistApplyAndResetWithoutChangingOtherPreferences() {
        openEditor()
        val original = device.findObject(By.desc("按键 q")).visibleBounds.height()
        openAppearance()
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("按屏宽自动调整高度"))
        device.waitForIdle()
        device.findObject(By.text("按屏宽自动调整高度")).click()
        maxSlider("竖屏按键行高")
        maxSlider("额外底部留白")
        maxSlider("按键间距")
        maxSlider("按键圆角")
        for (title in listOf("符号与数字分开", "字母副符号")) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(title))
            device.waitForIdle()
            device.findObject(By.text(title)).click()
        }
        assertEquals(36, KeyboardAppearance.read(context).bottomSpace)
        assertEquals(7, KeyboardAppearance.read(context).keyGap)
        assertEquals(12, KeyboardAppearance.read(context).keyRadius)
        assertEquals(false, KeyboardAppearance.read(context).splitBottom)
        assertEquals(false, KeyboardAppearance.read(context).auxiliary)
        maxSlider("按键字号")
        maxSlider("中文候选字号")
        maxSlider("英文释义字号")
        assertTrue(KeyboardAppearance.read(context).rowHeight > 48)
        assertTrue(KeyboardAppearance.read(context).keySize > 24)
        assertTrue(KeyboardAppearance.read(context).candidateSize > 24)
        assertTrue(KeyboardAppearance.read(context).glossSize > 13)
        device.findObject(By.text("主题")).click(); device.waitForIdle()
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description("主题 Mocha · 夜紫"))
        click("主题 Mocha · 夜紫")
        device.waitForIdle()
        assertEquals("mocha", KeyboardAppearance.read(context).theme)
        val chosen = KeyboardAppearance.read(context)
        device.setOrientationLeft(); device.waitForIdle(); device.setOrientationNatural(); device.waitForIdle()
        assertEquals(chosen, KeyboardAppearance.read(context))
        shot("appearance-settings")
        openEditor()
        assertTrue("已存外观应在 IME 进程重新进入输入框后生效", device.findObject(By.desc("按键 q")).visibleBounds.height() > original)
        type("nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        shot("appearance-custom-dark")
        click("按键 空格")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 10000))
        openAppearance()
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("恢复默认外观"))
        android.os.SystemClock.sleep(700); device.waitForIdle()
        shot("appearance-before-reset")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "appearance-before-reset.xml"))
        device.wait(Until.findObject(By.text("恢复默认外观")), 10000)!!.click()
        device.waitForIdle(); instrumentation.waitForIdleSync()
        val resetDeadline = android.os.SystemClock.uptimeMillis() + 3000
        while (KeyboardAppearance.read(context) != KeyboardAppearance() && android.os.SystemClock.uptimeMillis() < resetDeadline) android.os.SystemClock.sleep(25)
        assertEquals("恢复按钮应已保存默认值", KeyboardAppearance(), KeyboardAppearance.read(context))
        shot("appearance-reset-before-scroll")
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("按屏宽自动调整高度"))
        assertTrue("恢复默认应启用自动高度", device.wait(Until.hasObject(By.text("按屏宽自动调整高度").checked(true)), 10000))
        device.waitForIdle()
        assertEquals(true, KeyboardAppearance.read(context).autoHeight)
        shot("appearance-after-reset")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "appearance-after-reset.xml"))
        assertEquals(KeyboardAppearance(), KeyboardAppearance.read(context))
        assertEquals(false, Preferences.read(context).getBoolean("mixed"))
        openEditor()
        assertEquals(original, device.findObject(By.desc("按键 q")).visibleBounds.height())
    }
    @Test fun expandedCandidatesKeepKeyboardHeightAndCommitRealText() {
        openEditor(); type("nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
        val top = device.findObject(By.desc("展开候选")).visibleBounds.top
        val editorBottom = device.findObject(By.desc("手机测试输入框")).visibleBounds.bottom
        shot("appearance-default")
        click("展开候选")
        assertTrue(device.wait(Until.hasObject(By.desc("收起候选")), 10000))
        assertEquals(top, device.findObject(By.desc("收起候选")).visibleBounds.top)
        assertEquals(editorBottom, device.findObject(By.desc("手机测试输入框")).visibleBounds.bottom)
        assertTrue(!device.hasObject(By.desc("按键 q")))
        shot("appearance-expanded")
        val items = device.findObjects(By.desc(Pattern.compile("候选 你好，.*hello.*" )).enabled(true))
        assertEquals("候选条和展开区各有一个完整词候选", 2, items.size)
        val selected = items.last()
        val text = selected.contentDescription.removePrefix("候选 ").substringBefore('，')
        selected.click()
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text(text)), 10000))
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q")), 10000))
    }
}
