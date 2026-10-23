// 注音触屏、硬件声调、一声空格，以及切回英文/全拼的真实 IME 回归。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class ZhuyinFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun selectScheme(label: String, value: String) {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("输入与候选")), 10000)!!.click()
        val selector = UiSelector().description("输入方案")
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(selector))
        android.os.SystemClock.sleep(1000)
        device.findObject(selector).click()
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.ListView")), 10000))
        // Spinner 弹出动画期间节点已出现，但其点击坐标仍会移动。
        android.os.SystemClock.sleep(700)
        if (device.findObject(By.clazz("android.widget.ListView")).findObject(By.text(label)) == null) {
            assertTrue(UiScrollable(UiSelector().className("android.widget.ListView")).scrollIntoView(UiSelector().text(label)))
            android.os.SystemClock.sleep(1000)
        }
        val list = device.findObject(By.clazz("android.widget.ListView"))
        val option = list.findObject(By.text(label))
        assertTrue("输入方案菜单应包含 $label", option != null)
        option!!.click()
        assertTrue(device.wait(Until.gone(By.clazz("android.widget.ListView")), 10000))
        device.waitForIdle()
        assertEquals(value, Preferences.read(context).getString("scheme"))
    }
    private fun editor(mode: String = "text") {
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.desc("手机测试输入框")), 10000))
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode $mode -f 0x10008000")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框")), 10000))
        device.waitForIdle()
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
    }
    private fun key(label: String) {
        val button = device.wait(Until.findObject(By.desc("按键 $label").enabled(true)), 10000)
        assertTrue("注音键盘应有可用的 $label 键", button != null)
        button!!.click()
    }
    private fun candidate(text: String, gloss: String = ".*") {
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 $text，.*$gloss.*")).enabled(true)), 10000))
    }
    private fun text(value: String) {
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text(value)), 10000))
    }
    private fun shot(name: String) = device.takeScreenshot(File(context.getExternalFilesDir(null), "zhuyin-$name.png"))
    @Test fun touchAndHardwareTonesKeepCandidatesAndOtherSchemesWorking() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            selectScheme("大千注音", "zhuyin")
            editor()
            for (glyph in listOf("ㄋ", "ㄧ", "ˇ", "ㄏ", "ㄠ", "ˇ")) key(glyph)
            candidate("你好", "hello")
            text("ㄋㄧˇㄏㄠˇ")
            assertTrue(device.wait(Until.hasObject(By.desc("ㄋㄧˇㄏㄠˇ")), 10000))
            shot("touch-candidates")
            key("空格")
            text("你好")
            editor()
            device.executeShellCommand("input text wu0")
            key("空格")
            text("ㄊㄧㄢ")
            candidate("天")
            key("空格")
            text("天")
            editor()
            device.executeShellCommand("input text 1j4")
            candidate("不")
            device.executeShellCommand("input keyevent 62")
            text("不")
            device.setOrientationLeft()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 ㄡ").enabled(true)), 10000))
            key("ㄕ"); key("ㄡ"); key("ˇ")
            candidate("手")
            shot("landscape")
            // Core 词库按无调拼音排序，手并非必然首项；显式点选已显示的目标。
            device.wait(Until.findObject(By.desc(Pattern.compile("候选 手，.*")).enabled(true)), 10000)!!.click()
            text("不手")
            device.setOrientationNatural()
            device.wait(Until.findObject(By.desc("工具 english")), 10000)!!.click()
            key("r")
            device.executeShellCommand("input text ecieve")
            candidate("receive")
            shot("english")
            key("空格")
            text("不手receive ")
            device.wait(Until.findObject(By.desc("工具 english")), 10000)!!.click()
            editor("password")
            key("q"); key("w")
            // 密码字段按键仍是原始字母，不能展示注音键盘。
            assertTrue(device.hasObject(By.desc("按键 q")))
            selectScheme("全拼", "pinyin")
            editor()
            device.executeShellCommand("input text nihao")
            candidate("你好", "hello")
            device.executeShellCommand("input keyevent 8")
            text("你好")
            shot("pinyin-restored")
        } catch (error: Throwable) {
            shot("failure")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "zhuyin-failure.xml"))
            throw error
        } finally {
            Preferences.save(context, saved)
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun preeditSymbolsSurviveDeletionPartialSelectionCursorAndFocus() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        fun field(label: String) = device.wait(Until.findObject(By.desc(label)), 10000)!!
        fun expectField(label: String, value: String) {
            assertTrue("$label 应显示 $value", device.wait(Until.hasObject(By.desc(label).text(value)), 10000))
        }
        try {
            Preferences.save(context, JSONObject().put("scheme", "zhuyin"))
            device.setOrientationNatural()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            editor()
            device.executeShellCommand("input text su3cl3")
            text("ㄋㄧˇㄏㄠˇ")
            candidate("你好", "hello")
            device.executeShellCommand("input keyevent 67")
            text("ㄋㄧˇㄏㄠ")
            key("ˇ")
            text("ㄋㄧˇㄏㄠˇ")
            val partial = By.desc(Pattern.compile("候选 你，.*")).enabled(true)
            for (attempt in 0 until 8) {
                if (device.hasObject(partial)) break
                device.findObject(By.clazz("android.widget.HorizontalScrollView").pkg("dev.qingstudy.ime"))!!
                    .scroll(Direction.RIGHT, 0.5f, 300)
            }
            assertTrue(device.hasObject(partial))
            device.findObject(partial)!!.click()
            text("你ㄏㄠˇ")
            shot("partial-preedit")
            key("空格")
            text("你好")
            editor()
            device.executeShellCommand("input text wu0")
            text("ㄊㄧㄢ")
            key("空格")
            text("ㄊㄧㄢ")
            key("⌫") // 先删除不显示的一声；后续键仍操作原始引擎组合。
            text("ㄊㄧㄢ")
            key("⌫")
            text("ㄊㄧ")
            device.executeShellCommand("input text 0")
            key("空格"); key("空格")
            text("天")
            editor()
            device.executeShellCommand("input text su3")
            text("ㄋㄧˇ")
            device.executeShellCommand("input keyevent 21") // 既有行为：先原样提交再移动应用光标。
            device.executeShellCommand("input keyevent 67")
            text("ㄋˇ")
            device.executeShellCommand("input text cl3")
            text("ㄋㄏㄠˇˇ")
            key("空格")
            text("ㄋ好ˇ")
            device.pressHome()
            device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.EditorFixtureActivity -f 0x10008000")
            field("外部文本框一").click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 ㄋ").enabled(true)), 10000))
            device.executeShellCommand("input text wu0")
            key("空格")
            expectField("外部文本框一", "ㄊㄧㄢ")
            field("外部文本框二").click()
            assertTrue(device.wait(Until.hasObject(By.desc("大千注音 · 英文释义 · 本地运行")), 10000))
            device.executeShellCommand("input text su3cl3")
            expectField("外部文本框二", "ㄋㄧˇㄏㄠˇ")
            candidate("你好", "hello")
            key("空格")
            expectField("外部文本框二", "你好")
            expectField("外部文本框一", "ㄊㄧㄢ")
            shot("focus-preedit")
        } catch (error: Throwable) {
            shot("preedit-failure")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "zhuyin-preedit-failure.xml"))
            throw error
        } finally {
            Preferences.save(context, saved)
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
}
