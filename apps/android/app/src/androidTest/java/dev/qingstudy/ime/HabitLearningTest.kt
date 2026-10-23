// 真实设置页切换输入习惯学习，核对六张习惯表停写、词汇/统计继续及旧总开关。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
class HabitLearningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val habitFiles = listOf("frequency.tsv", "user-words.tsv", "user-ngram.tsv", "user-choices.tsv", "user-english.tsv", "user-typos.tsv")
    private fun snapshot(): List<String> {
        val directory = LearningBackupFiles.directory(context)
        return (habitFiles + listOf("vocabulary.tsv", "usage.tsv")).map { name ->
            File(directory, name).let { if (it.exists()) it.readText() else "" }
        }
    }
    private fun total(): Long = JSONObject(File(LearningBackupFiles.directory(context), "summary.json").readText()).getLong("total")
    private fun openEditor() {
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.desc("手机测试输入框")), 10000))
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode text -f 0x10008000")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框")), 10000))
        device.waitForIdle()
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
    }
    private fun commit() {
        device.executeShellCommand("input text nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 10000))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 10000))
    }
    private fun toggle(title: String, key: String, enabled: Boolean) {
        // 使用正式学习页入口，专注验证开关与写入，不依赖首页是否可滚动。
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("page", "learning")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("本机学习、词汇记录与统计")), 10000))
        device.waitForIdle()
        val selector = UiSelector().text(title)
        if (!device.hasObject(By.text(title))) {
            assertTrue(device.wait(Until.hasObject(By.scrollable(true)), 10000))
            assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(selector))
        }
        android.os.SystemClock.sleep(1000)
        device.waitForIdle()
        assertEquals(!enabled, device.findObject(selector).isChecked)
        device.findObject(selector).click()
        assertTrue(device.wait(Until.hasObject(By.text(title).checked(enabled)), 10000))
        assertEquals(enabled, Preferences.read(context).getBoolean(key))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "habits-$key-$enabled.png"))
    }
    @Test fun habitsOffKeepsVocabularyWhileMasterOffStillStopsEverything() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            // 只有旧键：升级不改变既有用户的默认输入习惯学习。
            Preferences.save(context, JSONObject().put("learning", true))
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            openEditor()
            commit()
            val before = snapshot()
            val count = total()
            toggle("学习输入习惯", "habitLearning", false)
            assertTrue(Preferences.read(context).getBoolean("learning"))
            openEditor()
            commit()
            val after = snapshot()
            assertEquals(before.take(6), after.take(6))
            assertFalse(before[6] == after[6])
            assertEquals(count + 2, total())
            device.takeScreenshot(File(context.getExternalFilesDir(null), "habits-off-committed.png"))

            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            openEditor()
            commit()
            assertEquals(before.take(6), snapshot().take(6))
            assertEquals(count + 4, total())
            toggle("学习输入习惯", "habitLearning", true)
            openEditor()
            commit()
            assertFalse(before[0] == snapshot()[0])
            toggle("本机学习、词汇记录与统计", "learning", false)
            val stopped = snapshot()
            openEditor()
            commit()
            assertEquals(stopped, snapshot())
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "habits-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "habits-failure.xml"))
            throw error
        } finally {
            Preferences.save(context, saved)
            device.unfreezeRotation()
        }
    }
}
