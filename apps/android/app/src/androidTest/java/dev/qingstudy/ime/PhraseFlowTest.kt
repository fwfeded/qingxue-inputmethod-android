// 手机界面管理短语，真实 IME 验证预览、数字/空格提交、启停和编辑持久性。
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class PhraseFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) { device.wait(Until.findObject(By.text(text)), 10000)!!.click() }
    private fun field(label: String): androidx.test.uiautomator.UiObject2 {
        device.wait(Until.findObject(By.desc(label)), 2000)?.let { return it }
        if (device.hasObject(By.scrollable(true))) {
            // 窄屏/大字体下编辑表单需要滚动，不能假定所有字段同时可见。
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(label))
        }
        return device.wait(Until.findObject(By.desc(label)), 10000)!!
    }
    private fun open() {
        context.startActivity(Intent(context, PhraseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.desc("短语状态").textContains("已保存")), 10000))
    }
    private fun fill(code: String, position: String, text: String) {
        field("短语输入码").text = code; field("短语位置").text = position; field("短语文本").text = text
    }
    private fun input(pinyin: String) {
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.EditorFixtureActivity -f 0x10008000")
        field("外部文本框一").click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.executeShellCommand("input text $pinyin")
    }
    private fun expect(text: String) { assertTrue(device.wait(Until.hasObject(By.desc("外部文本框一").text(text)), 10000)) }
    @Test fun editToggleConflictRotationAndExactCommit() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val original = "第一行 地址：甲乙\n第二行 😀\tEND"
        val preview = "第一行 地址：甲乙↵第二行 😀⇥END"
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("词库与短语")), 10000)!!.click()
            tap("自定义短语")
            tap("新增短语")
            field("短语输入码").click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
            device.executeShellCommand("input text nihao")
            device.executeShellCommand("input keyevent 62")
            assertEquals("输入码字段应保留字母", "nihao ", field("短语输入码").text)
            device.executeShellCommand("input keyevent 67")
            fill("nihao", "2", original); tap("保存规则")
            assertTrue(device.wait(Until.gone(By.desc("短语输入码")), 10000))
            assertEquals(original, Preferences.read(context).getJSONArray("customPhrases").getJSONObject(0).getString("text"))
            input("nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
            val candidate = device.wait(Until.findObject(By.desc(Pattern.compile("候选 ${Pattern.quote(preview)}，.*"))), 10000)!!
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phrase-candidate-preview.png"))
            candidate.longClick()
            assertTrue(device.wait(Until.hasObject(By.text(original)), 10000))
            tap("返回键盘")
            val focusDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (!instrumentation.uiAutomation.windows.any { it.isFocused && it.root?.packageName == "dev.qingstudy.ime.test" } && android.os.SystemClock.uptimeMillis() < focusDeadline) android.os.SystemClock.sleep(20)
            assertTrue("关闭详情后编辑器窗口应恢复焦点", instrumentation.uiAutomation.windows.any { it.isFocused && it.root?.packageName == "dev.qingstudy.ime.test" })
            device.executeShellCommand("input keyevent 9") // 实体 2 选择第二项。
            expect(original)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phrase-multiline-commit.png"))
            open(); tap("新增短语"); fill("nihao", "2", "冲突不能覆盖"); tap("保存规则")
            assertTrue(device.wait(Until.hasObject(By.desc("短语校验错误").textContains("已被占用")), 10000))
            assertEquals(original, Preferences.read(context).getJSONArray("customPhrases").getJSONObject(0).getString("text"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phrase-conflict.png"))
            tap("取消")
            // 关闭冲突弹窗后，底层开关会先出现在无障碍树中；等窗口切换完成再触摸。
            assertTrue(device.wait(Until.gone(By.desc("短语输入码")), 10000))
            device.waitForIdle()
            device.findObject(UiSelector().description("启用短语 nihao 第2位")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("启用短语 nihao 第2位").checked(false)), 5000))
            assertFalse(Preferences.read(context).getJSONArray("customPhrases").getJSONObject(0).getBoolean("enabled"))
            input("nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 10000))
            assertFalse(device.hasObject(By.desc(Pattern.compile("候选 ${Pattern.quote(preview)}，.*"))))
            device.executeShellCommand("input keyevent 62"); expect("你好")
            open(); field("启用短语 nihao 第2位").click(); field("编辑短语 nihao 第2位").click()
            val changed = "  新地址😀\n第二行  "
            fill("qqq", "1", changed)
            device.setOrientationLeft(); device.waitForIdle(); device.setOrientationNatural(); device.waitForIdle()
            assertEquals(changed, field("短语文本").text)
            tap("保存规则")
            assertTrue(device.wait(Until.gone(By.desc("短语输入码")), 10000))
            input("qqq")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 .*新地址.*")).enabled(true)), 10000))
            device.executeShellCommand("input keyevent 62"); expect(changed)
            open(); field("删除短语 qqq 第1位").click()
            device.wait(Until.findObject(By.res("android:id/button1")), 10000)!!.click()
            assertTrue(device.wait(Until.gone(By.desc("删除短语 qqq 第1位")), 10000))
            assertEquals(0, Preferences.read(context).getJSONArray("customPhrases").length())
            input("nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 10000))
            device.executeShellCommand("input keyevent 62"); expect("你好")
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phrase-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "phrase-failure.xml"))
            throw error
        } finally { device.setOrientationNatural(); device.unfreezeRotation(); Preferences.save(context, saved) }
    }
}
