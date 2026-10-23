// 真实拼音/释义输入后，经设置页核对分级数字、滚动与关闭释义时的数据保留。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.core.app.ActivityScenario
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
class VocabularyLevelsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) {
        device.waitForIdle()
        val selector = UiSelector().text(text)
        val item = device.findObject(selector)
        val bounds = if (item.exists()) item.visibleBounds else android.graphics.Rect()
        if (bounds.isEmpty || bounds.centerY() !in (device.displayHeight / 10)..(device.displayHeight * 9 / 10)) {
            assertTrue("应能滚动至：$text", UiScrollable(UiSelector().scrollable(true)).scrollIntoView(selector))
            // ScrollView 的触底回弹不属于 accessibility idle；稳定后只点击一次。
            android.os.SystemClock.sleep(1000)
        }
        device.waitForIdle()
        // scrollIntoView 只保证部分可见；额外移动到安全区域，不能把导航栏边缘当按钮中心。
        repeat(4) {
            val target = device.findObject(selector)
            val visible = target.visibleBounds
            if (!visible.isEmpty && visible.centerY() in (device.displayHeight / 10)..(device.displayHeight * 9 / 10)) {
                target.click()
                return
            }
            val scroll = UiScrollable(UiSelector().scrollable(true))
            if (!visible.isEmpty && visible.centerY() < device.displayHeight / 10) scroll.scrollBackward(120)
            else scroll.scrollForward(120)
            android.os.SystemClock.sleep(1000)
            device.waitForIdle()
        }
        error("按钮未进入安全点击区域：$text")
    }
    private fun input(gloss: Boolean) {
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.desc("手机测试输入框")), 10000))
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode text -f 0x10008000")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框")), 10000))
        device.waitForIdle()
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.executeShellCommand("input text nihao")
        val candidate = device.wait(Until.findObject(By.desc(Pattern.compile("候选 你好，.*")).enabled(true)), 10000)!!
        assertEquals(gloss, candidate.contentDescription.contains("hello"))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 10000))
    }
    private fun showLevels() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("学习与隐私")), 10000)!!.click()
        tap("查看学习记录与统计")
        assertTrue(device.wait(Until.hasObject(By.text("本机学习记录")), 10000))
        tap("英语分级统计")
        assertTrue(device.wait(Until.hasObject(By.text("英语词汇分级（A1–C2）")), 10000))
    }
    private fun assertReadable(prefix: String) {
        val selector = UiSelector().textStartsWith(prefix)
        val item = device.findObject(selector)
        // 短状态提示可能整页可见，系统不会把 ScrollView 标为可滚动。
        if (!item.exists() || item.visibleBounds.isEmpty) {
            assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(selector))
        }
        device.waitForIdle()
        val row = device.findObject(selector)
        val exposed = row.contentDescription?.takeIf { it.isNotBlank() } ?: row.text
        assertTrue("无障碍文本不应被简短标签覆盖：$prefix；实际：$exposed", exposed.contains(row.text))
    }
    @Test fun fallbackStatesExposeFullTextToAccessibility() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((snapshot, prefix) in listOf(
                JSONObject() to "英语分级统计将在下次输入后更新",
                JSONObject().put("levels", org.json.JSONArray()) to "英语等级表暂不可用",
                JSONObject().put("learningLanguage", "off") to "英语分级统计已暂停"
            )) {
                scenario.onActivity { VocabularyLevels.show(it, snapshot) }
                assertTrue(device.wait(Until.hasObject(By.textStartsWith(prefix)), 10000))
                assertReadable(prefix)
                assertReadable("来源：CEFR-J")
                tap("关闭")
                assertTrue(device.wait(Until.gone(By.text("英语词汇分级（A1–C2）")), 10000))
            }
        }
    }
    @Test fun levelsUiUsesCoreCountsAndSurvivesDisabledGlossAndServiceReload() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject().put("learning", true))
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            input(true)
            val directory = LearningBackupFiles.directory(context)
            val snapshot = JSONObject(File(directory, "summary.json").readText())
            val levels = snapshot.getJSONArray("levels")
            assertEquals(6, levels.length())
            assertTrue(levels.getJSONObject(0).getLong("seen") >= 2)
            assertTrue(levels.getJSONObject(0).getLong("committed") >= 2)
            showLevels()
            assertReadable("按不同译词计数")
            for (i in 0 until levels.length()) {
                val level = levels.getJSONObject(i)
                val name = level.getString("name")
                assertReadable("$name：")
                val row = device.findObject(UiSelector().textStartsWith("$name："))
                assertEquals("$name：见过 ${level.getLong("seen")} / 词表 ${level.getLong("total")}\n看熟 ${level.getLong("familiar")} · 选词 ${level.getLong("committed")}", row.text)
                assertTrue(row.visibleBounds.height() > 0)
                if (i == 0 || i == 5) device.takeScreenshot(File(context.getExternalFilesDir(null), "levels-$name.png"))
            }
            assertReadable("未收录的译词")
            assertReadable("来源：CEFR-J")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "levels-readable.xml"))
            tap("关闭")
            val vocabulary = File(directory, "vocabulary.tsv").readText()
            Preferences.save(context, Preferences.read(context).put("learningLanguage", "off"))
            input(false)
            assertEquals(vocabulary, File(directory, "vocabulary.tsv").readText())
            showLevels()
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("英语分级统计已暂停")), 10000))
            assertFalse(device.hasObject(By.textStartsWith("A1：")))
            assertReadable("英语分级统计已暂停")
            device.takeScreenshot(File(context.getExternalFilesDir(null), "levels-paused.png"))
            tap("关闭")
            Preferences.save(context, Preferences.read(context).put("learningLanguage", "en").put("learning", false))
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            input(true)
            assertEquals(vocabulary, File(directory, "vocabulary.tsv").readText())
            assertEquals(levels.toString(), JSONObject(File(directory, "summary.json").readText()).getJSONArray("levels").toString())
            showLevels()
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("A1：")), 10000))
            tap("关闭")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("青学")), 10000))
            tap("关于与帮助")
            tap("开源与数据许可")
            assertTrue(device.wait(Until.hasObject(By.res("android:id/message").textContains("Yukio Tono")), 10000))
            assertTrue(device.findObject(UiSelector().resourceId("android:id/message")).text.contains("CC BY-SA 4.0"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "levels-attribution.png"))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "levels-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "levels-failure.xml"))
            throw error
        } finally {
            Preferences.save(context, saved)
            device.unfreezeRotation()
        }
    }
}
