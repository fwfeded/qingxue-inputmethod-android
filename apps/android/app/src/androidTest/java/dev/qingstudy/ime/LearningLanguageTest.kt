// 释义设置经真实页面切换，并在系统 IME 验证上屏、词汇保留与服务重建。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class LearningLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun key(label: String) {
        repeat(3) { attempt ->
            try {
                device.wait(Until.findObject(By.desc("按键 $label").enabled(true)), 10000)!!.click()
                return
            } catch (stale: androidx.test.uiautomator.StaleObjectException) {
                // The exception is raised while resolving bounds, before any touch is sent.
                // Reacquire the IME node after service/layout replacement; never retry a sent tap.
                if (attempt == 2) throw stale
                device.waitForIdle()
            }
        }
    }
    private fun candidate(text: String): UiObject2 = device.wait(Until.findObject(
        By.desc(Pattern.compile("候选 ${Pattern.quote(text)}，.*")).enabled(true)), 15000)!!
    private fun open() {
        // 前一用例也可能停在同名编辑框；确认旧页面退场，避免抓到 CLEAR_TASK 替换中的旧节点。
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.desc("手机测试输入框")), 10000))
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
            .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框")), 10000))
        device.waitForIdle()
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.waitForIdle()
    }
    private fun type(value: String) { value.forEach { key(it.toString()) } }
    private fun expect(value: String) {
        assertTrue("expected $value", device.wait(Until.hasObject(By.desc("手机测试输入框").text(value)), 10000))
    }
    private fun setLanguage(enabled: Boolean) {
        device.wait(Until.findObject(By.desc("工具 settings").enabled(true)), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.scrollable(true)),10000))
        if (!device.hasObject(By.text("学习与隐私"))) {
            assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("学习与隐私")))
            device.waitForIdle();android.os.SystemClock.sleep(500)
        }
        device.wait(Until.findObject(By.text("学习与隐私")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("本机学习、词汇记录与统计")), 10000))
        device.waitForIdle()
        if (!device.hasObject(By.text("中文候选英文释义"))) {
            assertTrue(device.wait(Until.hasObject(By.scrollable(true)), 10000))
            assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("中文候选英文释义")))
        }
        device.waitForIdle()
        device.findObject(UiSelector().text("中文候选英文释义")).click()
        assertTrue(device.wait(Until.hasObject(By.text("中文候选英文释义").checked(enabled)), 10000))
        device.waitForIdle()
        assertEquals(if (enabled) "en" else "off", Preferences.read(context).getString("learningLanguage"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "language-settings-${if (enabled) "on" else "off"}.png"))
        // 返回键离开滚动后的设置页，回到原编辑器。
        device.pressBack(); device.pressBack()
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 10000))
    }

    @Test fun offOnAndRecreatedServiceKeepInputLearningAndExistingWords() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject().put("learning", true))
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            open()
            type("kaifa")
            assertTrue(candidate("开发").contentDescription.contains("develop"))
            key("空格")
            expect("开发")
            val directory = LearningBackupFiles.directory(context)
            val vocabulary = File(directory, "vocabulary.tsv").readText()
            val frequency = File(directory, "frequency.tsv").readText()
            val before = JSONObject(File(directory, "summary.json").readText()).getLong("total")
            setLanguage(false)
            assertTrue(Preferences.read(context).getBoolean("learning"))
            type("nihao")
            val plain = candidate("你好")
            assertFalse(plain.contentDescription.contains("hello"))
            plain.longClick()
            assertTrue(device.wait(Until.hasObject(By.text("完整释义")), 10000))
            assertTrue(device.hasObject(By.text("你好")))
            assertFalse(device.hasObject(By.text(Pattern.compile("输入译词：.+"))))
            device.wait(Until.findObject(By.desc("工具 close")), 10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").focused(true)), 10000))
            key("空格")
            expect("开发你好")
            assertEquals(vocabulary, File(directory, "vocabulary.tsv").readText())
            assertFalse(frequency == File(directory, "frequency.tsv").readText())
            assertEquals(before + 2, JSONObject(File(directory, "summary.json").readText()).getLong("total"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "language-off-committed.png"))
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            open()
            type("nihao")
            assertFalse(candidate("你好").contentDescription.contains("hello"))
            key("空格")
            expect("你好")
            assertEquals(vocabulary, File(directory, "vocabulary.tsv").readText())
            device.wait(Until.findObject(By.desc("工具 english")), 10000)!!.click()
            type("recieve")
            val english = candidate("receive")
            assertTrue(english.contentDescription.contains("收到"))
            english.click()
            expect("你好receive")
            device.wait(Until.findObject(By.desc("工具 english")), 10000)!!.click()
            setLanguage(true)
            type("nihao")
            val restored = candidate("你好")
            assertTrue(restored.contentDescription.contains("hello"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "language-on-candidates.png"))
            restored.longClick()
            device.wait(Until.findObject(By.text("输入译词：hello")), 10000)!!.click()
            expect("你好receivehello")
            assertEquals("en", JSONObject(File(directory, "summary.json").readText()).getString("learningLanguage"))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "language-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "language-failure.xml"))
            throw error
        } finally {
            Preferences.save(context, saved)
            device.unfreezeRotation()
        }
    }
}
