// 原键区内的工具和释义窗口，真实拼音与上屏语义检查。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class InlinePanelTest {
    @Test fun detailsAndToolsStayInsideKeyboardAndCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        fun click(desc: String) { device.wait(Until.findObject(By.desc(desc).enabled(true)), 15000)!!.click() }
        fun shot(name: String) { device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")) }
        try {
            Preferences.save(context, JSONObject())
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
                .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("手机测试输入框")
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
            "nihao".forEach { click("按键 $it") }
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
            val top = device.findObject(By.desc("工具 menu")).visibleBounds.top
            shot("r2-keyboard")
            click("工具 details")
            assertTrue(device.wait(Until.hasObject(By.text("完整释义")), 10000))
            assertEquals(top, device.findObject(By.desc("工具 menu")).visibleBounds.top)
            assertTrue(!device.hasObject(By.desc("按键 q")))
            shot("r2-inline-gloss")
            click("输入中文")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 10000))
            assertTrue(device.wait(Until.hasObject(By.desc("按键 q")), 10000))
            click("工具 menu")
            assertEquals(top, device.findObject(By.desc("工具 menu")).visibleBounds.top)
            shot("r2-tools")
            click("工具 close")
            assertTrue(device.wait(Until.hasObject(By.desc("按键 q")), 10000))
        } finally { Preferences.save(context, saved); device.unfreezeRotation() }
    }
}
