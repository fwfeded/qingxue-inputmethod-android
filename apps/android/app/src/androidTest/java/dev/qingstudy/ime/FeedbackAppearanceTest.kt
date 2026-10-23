//! 已确认外观方案的真实设置页契约：固定预览、主题保存和反馈控件。
package dev.qingstudy.ime

import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FeedbackAppearanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var saved: JSONObject

    @Before fun prepare() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject().put("mixed", false))
        device.setOrientationNatural()
    }
    @After fun restore() {
        Preferences.save(context, saved)
        device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun open() {
        context.startActivity(Intent(context, AppearanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("键盘与外观")), 10000))
        device.waitForIdle()
    }
    @Test fun scrollingSettingsKeepsLivePreviewVisible() {
        open()
        device.findObject(By.text("尺寸与文字"))?.click()
        val selector = By.desc("候选 你好，hello; hi")
        val before = device.wait(Until.findObject(selector), 5000)!!.visibleBounds
        UiScrollable(UiSelector().scrollable(true)).scrollToEnd(20)
        device.waitForIdle()
        device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-preview-bottom.png"))
        assertTrue("滚到底部后候选预览仍应可见", device.hasObject(selector))
        assertEquals("滚动只能移动设置，不能移动预览", before, device.findObject(selector).visibleBounds)
    }
    @Test fun allSixThemeIdsSurviveReload() {
        for (theme in listOf("qing", "latte", "frappe", "macchiato", "mocha", "nord")) {
            val appearance = KeyboardAppearance.fromJson(JSONObject().put("theme", theme))
            KeyboardAppearance.save(context, appearance)
            assertEquals("主题必须跨进程保存", theme, KeyboardAppearance.read(context).theme)
        }
        assertEquals(false, Preferences.read(context).getBoolean("mixed"))
    }
    @Test fun feedbackControlsAreDiscoverable() {
        open()
        device.findObject(By.text("按键反馈"))?.click()
        assertTrue("需要独立的按键震动开关", device.wait(Until.hasObject(By.text("按键震动")), 5000))
        assertTrue(device.hasObject(By.text("震动跟随系统")))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("按键音效"))
        assertTrue(device.hasObject(By.text("按键音效")))
    }
    @Test fun fixedTabLabelsFitPortraitAndLandscape() {
        ActivityScenario.launch(AppearanceActivity::class.java).use { scenario ->
            for (landscape in listOf(false, true)) {
                if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
                device.waitForIdle()
                val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ready = it.resources.configuration.orientation == expected && it.window.decorView.width > 0 }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("旋转布局应完成", ready)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val found = mutableListOf<Button>()
                    fun visit(view: View) {
                        if (view is Button && view.text.toString() in listOf("主题", "按键反馈", "尺寸与文字")) found.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                    }
                    visit(activity.window.decorView)
                    assertEquals(3, found.size)
                    for (button in found) {
                        val available = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                        val textWidth = button.paint.measureText(button.text.toString())
                        assertTrue("${button.text} 文字宽 $textWidth 超过按钮 $available，横屏=$landscape", textWidth <= available + 1)
                        assertEquals(1, button.layout.lineCount)
                    }
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-tabs-fit-$landscape.png"))
            }
        }
    }
}
