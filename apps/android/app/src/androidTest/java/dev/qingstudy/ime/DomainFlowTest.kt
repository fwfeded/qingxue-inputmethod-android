// 领域开关经真实设置页保存；外部编辑器验证中文首选、英文释义和空格上屏。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class DomainFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 2000)) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        device.findObject(UiSelector().text(text)).click()
    }
    private fun domain(name: String): UiObject {
        val selector = UiSelector().description("领域词库 $name")
        // accessibility 可枚举屏幕外的 Switch；exists 不意味着可安全点击。
        assertTrue("应在词库页面：$name", device.wait(Until.hasObject(By.pkg("dev.qingstudy.ime")), 5000))
        // 部分可见项的中心可能落入系统栏；快速滚动触底后的回弹还会吞掉首个点击。
        // 慢速拖动，留出回弹时间后重新取坐标，保持一次点击的真实操作断言。
        repeat(12) {
            device.waitForIdle()
            val item = device.findObject(selector)
            val bounds = if (item.exists()) item.visibleBounds else android.graphics.Rect()
            if (!bounds.isEmpty && bounds.centerY() in (device.displayHeight / 10)..(device.displayHeight * 9 / 10)) return item
            val x = device.displayWidth / 2
            val top = device.displayHeight * 3 / 10
            val bottom = device.displayHeight * 7 / 10
            if (!bounds.isEmpty && bounds.centerY() < device.displayHeight / 10) device.swipe(x, top, x, bottom, 120)
            else device.swipe(x, bottom, x, top, 120)
            android.os.SystemClock.sleep(1000) // ScrollView 回弹不是 accessibility idle 事件。
        }
        fail("词库开关没有安全点击区域：$name")
        return device.findObject(selector)
    }
    private fun openDomains() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("词库与短语")), 10000)!!.click()
        tap("内置领域词库")
        assertTrue(device.wait(Until.hasObject(By.desc("领域词库状态").textContains("11")), 20000))
    }
    private fun input(pinyin: String, expected: String, english: String) {
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode text -f 0x10008000")
        assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.executeShellCommand("input text $pinyin")
        assertTrue("领域词和英文释义：$expected", device.wait(Until.hasObject(By.desc(Pattern.compile("候选 ${Pattern.quote(expected)}，.*${Pattern.quote(english)}.*")).enabled(true)), 20000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "domain-$pinyin.png"))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text(expected)), 10000))
    }
    @Test fun bundledTogglesPersistAndCandidatesHaveEnglish() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            openDomains()
            assertTrue(domain("成语").isChecked)
            assertFalse(domain("IT 与计算机").isChecked)
            domain("IT 与计算机").click()
            domain("医学").click()
            device.takeScreenshot(File(context.getExternalFilesDir(null), "domain-settings.png"))
            tap("返回设置")
            // 返回父页面后改另一设置，不能用旧的整份配置抹掉刚选的词库。
            device.pressBack()
            tap("输入与候选")
            tap("中文候选优先")
            assertEquals(3, Preferences.read(context).getJSONArray("domains").length())
            input("yiqiuyihe", "一丘一壑", "secluded")
            input("yizhixinghaxi", "一致性哈希", "consistent hashing")
            input("shenxiaoqiulvguolv", "肾小球滤过率", "glomerular filtration rate")
            openDomains()
            assertTrue(domain("IT 与计算机").isChecked)
            domain("IT 与计算机").click()
            tap("返回设置")
            tap("内置领域词库")
            assertTrue(device.wait(Until.hasObject(By.desc("领域词库状态").textContains("11")), 20000))
            assertFalse(domain("IT 与计算机").isChecked)
            // 每本都可通过手机界面启用，随后真实输入同时加载全部词库。
            for (name in listOf("成语", "IT 与计算机", "医学", "法律", "地名", "诗词名句", "财经", "饮食", "动物", "汽车", "历史人物")) {
                val toggle = domain(name)
                assertTrue("词库必须可用：$name", toggle.isEnabled)
                if (!toggle.isChecked) {
                    val bounds = toggle.visibleBounds
                    device.click(bounds.right - 24, bounds.centerY())
                }
                device.waitForIdle()
                assertTrue("点击后应开启：$name", device.wait(Until.hasObject(By.desc("领域词库 $name").checked(true)), 3000))
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            while (Preferences.read(context).getJSONArray("domains").length() != 11 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            assertEquals(Preferences.read(context).toString(), 11, Preferences.read(context).getJSONArray("domains").length())
            input("yizhixinghaxi", "一致性哈希", "consistent hashing")
            val rows = JSONArray(NativeBridge.domains(DomainFiles.prepare(context).absolutePath))
            for (i in 0 until rows.length()) { assertTrue(rows.getJSONObject(i).getInt("entries") > 0) }
            File(context.getExternalFilesDir(null), "domain-catalog.json").writeText(rows.toString(2))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "domain-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "domain-failure.xml"))
            throw error
        } finally { Preferences.save(context, saved) }
    }
}
