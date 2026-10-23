// 离线功能的真实系统 IME 实验；独立编辑器验证上屏，不以引擎存在代替 UI 可用。
package dev.qingstudy.ime
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class OfflineParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var device: UiDevice
    private lateinit var saved: JSONObject
    @Before fun prepare() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject())
        device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        device.setOrientationNatural()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() { Preferences.save(context, saved); device.unfreezeRotation() }
    private fun open(mode: String = "text") {
        context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
            .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 $mode")), 10000))
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 空格").enabled(true)), 30000))
    }
    private fun click(desc: String) { device.wait(Until.findObject(By.desc(desc).enabled(true)), 10000)!!.click() }
    private fun type(value: String) { value.forEach { click("按键 $it") } }
    private fun expect(value: String) { assertTrue("expected $value", device.wait(Until.hasObject(By.desc("手机测试输入框").text(value)), 10000)) }
    private fun candidate(word: String): UiObject2 = device.wait(Until.findObject(By.desc(Pattern.compile("候选 ${Pattern.quote(word)}，.*")).enabled(true)), 15000)!!
    private fun snapshot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")) }

    @Test fun englishCorrectionExpressionAndSymbolPanel() {
        open()
        click("工具 english")
        type("recieve")
        candidate("receive").click()
        expect("receive")
        snapshot("parity-english")
        click("工具 english")
        type("v")
        click("工具 menu"); click("工具 numbers")
        type("1+2")
        candidate("3")
        snapshot("parity-expression")
        click("按键 空格")
        expect("receive3")
        click("按键 ABC")
        click("工具 menu"); click("工具 panel")
        assertTrue(device.wait(Until.hasObject(By.desc("分类 常用")), 10000))
        click("工具 emoji")
        snapshot("parity-symbols")
        val symbol = device.wait(Until.findObject(By.clazz("android.widget.Button").desc(Pattern.compile("符号 .+")).enabled(true)), 10000)!!
        val text = symbol.text
        symbol.click()
        expect("receive3$text")
        click("按键 ABC")
        assertTrue(device.wait(Until.hasObject(By.desc("按键 q")), 10000))
    }

    @Test fun settingsShuangpinTraditionalAndTranslation() {
        open()
        click("工具 settings")
        device.wait(Until.findObject(By.text("输入与候选")), 10000)!!.click()
        click("输入方案")
        device.wait(Until.findObject(By.text("小鹤双拼")), 10000)!!.click()
        click("简繁输出")
        device.wait(Until.findObject(By.text("繁体（台湾）")), 10000)!!.click()
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("返回输入"))
        device.waitForIdle(); android.os.SystemClock.sleep(500)
        device.wait(Until.findObject(By.text("返回输入")), 10000)!!.click()
        device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
        type("kdfa")
        val selected = candidate("開發")
        snapshot("parity-shuangpin-traditional")
        selected.longClick()
        val translation = device.wait(Until.findObject(By.text(Pattern.compile("输入译词：.+"))), 10000)!!
        val word = translation.text.removePrefix("输入译词：")
        snapshot("parity-full-gloss")
        translation.click()
        expect(word)
    }

    @Test fun learningPersistsButPrivateEditorDoesNotRecord() {
        Preferences.save(context, JSONObject().put("learning", true))
        open()
        type("kaifa")
        candidate("开发")
        click("按键 空格")
        expect("开发")
        val dir = LearningBackupFiles.directory(context)
        val before = JSONObject(File(dir, "summary.json").readText())
        assertTrue(before.getLong("total") >= 2)
        assertTrue(before.getLong("committed") > 0)
        val frequency = File(dir, "frequency.tsv").readText()
        open("private")
        type("yingyu")
        click("按键 空格")
        expect("英语")
        assertEquals(frequency, File(dir, "frequency.tsv").readText())
        assertEquals(before.toString(), JSONObject(File(dir, "summary.json").readText()).toString())
        open("password")
        type("nihao")
        assertEquals(frequency, File(dir, "frequency.tsv").readText())
        snapshot("parity-private")
        open()
        assertEquals(before.toString(), JSONObject(File(dir, "summary.json").readText()).toString())
    }
    @Test fun pagingSpaceSelectsCurrentPage() {
        open()
        type("shi")
        candidate("是")
        click("工具 menu"); click("工具 next")
        device.waitForIdle()
        val first = device.wait(Until.findObject(By.desc(Pattern.compile("候选 .+" )).enabled(true)), 10000)!!
        val expected = first.contentDescription.removePrefix("候选 ").substringBefore('，')
        assertNotEquals("翻页应离开首选", "是", expected)
        snapshot("parity-page")
        click("按键 空格")
        expect(expected)
    }

}
