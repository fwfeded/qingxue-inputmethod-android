//! 在真实IME窗口验证长按选择、取消和开关；保留现有字词与释义路径。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class KeyAlternativesFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val host get() = By.res(context.packageName, "test_input")
    private lateinit var saved: JSONObject

    @Before fun prepare() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject())
        device.setOrientationNatural()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun restore() {
        device.pressBack()
        Preferences.save(context, saved)
        device.unfreezeRotation()
    }
    private fun key(name: String) = device.wait(Until.findObject(By.desc(name).enabled(true)), 20000)
        ?: error("缺少 $name")
    private fun start() {
        context.startActivity(Intent(context, TryInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(host), 15000)!!.click()
        key("按键 空格")
        device.waitForKeyboardGeometry()
    }
    private fun type(value: String) { value.forEach { key("按键 $it").click() } }
    private fun expect(value: String) {
        val matched = device.wait(Until.hasObject(host.text(value)), 10000)
        if (!matched) shot("unexpected-text")
        assertTrue("应上屏 $value，实际为${device.findObject(host)?.text}", matched)
    }
    private fun open(letter: String) {
        key("按键 $letter").longClick()
        val choice = device.wait(Until.findObject(By.text(letter.uppercase()).clazz("android.widget.TextView")), 5000)
        if (choice == null) shot("missing-$letter")
        assertNotNull("长按${letter}缺少大写选项；文字=${device.findObject(host)?.text}；菜单=${device.findObjects(By.res("android", "title")).map { it.text }}", choice)
    }
    private fun select(value: String) {
        device.wait(Until.findObject(By.text(value).clazz("android.widget.TextView")), 5000)!!.click()
        device.waitForIdle()
    }
    private fun candidate() = By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)
    private fun shot(name: String) {
        device.takeScreenshot(File(context.getExternalFilesDir(null), "alternatives-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "alternatives-$name.xml"))
    }

    @Test fun chineseCompositionCanCommitAnExplicitCaseWithoutAddingPinyin() {
        start(); type("nihao")
        assertTrue(device.wait(Until.hasObject(candidate()), 10000))
        open("q"); shot("q"); select("Q"); expect("你好Q")
        open("w"); select("w"); expect("你好Qw")
        assertTrue(device.findObjects(By.desc(Pattern.compile("候选 .*"))).isEmpty())
        type("nihao")
        assertTrue("继续输入仍有英文释义", device.wait(Until.hasObject(candidate()), 10000))
        key("按键 空格").click(); expect("你好Qw你好")
    }
    @Test fun hiddenSecondarySymbolsStillAllowCaseSelection() {
        KeyboardAppearance.save(context, KeyboardAppearance(auxiliary = false))
        start(); open("q")
        assertEquals("隐藏副符号时只保留大小写", listOf("Q", "q"),
            device.findObjects(By.res("android", "title")).map { it.text })
        shot("letters-only"); select("q"); expect("q")
    }
    @Test fun cancelPreservesCompositionAndRightEdgeOptionsAreReachable() {
        start(); type("nihao"); open("p"); shot("right-edge")
        device.pressBack()
        assertTrue("取消不应提交或追加p", device.wait(Until.hasObject(candidate()), 10000))
        key("按键 空格").click(); expect("你好")
        open("l"); select("L"); expect("你好L")
        open("e"); shot("extended"); select("€"); expect("你好L€")
    }
    @Test fun englishCompositionUsesExactCaseAndKeepsSymbolChoices() {
        start(); key("工具 english").click(); type("hello")
        open("q"); select("Q"); expect("helloQ")
        open("w"); select("2"); expect("helloQ2")
        open("q"); select("q"); expect("helloQ2q")
        key("按键 a").click(); expect("helloQ2qa")
    }
    @Test fun disabledLongPressStillTypesOnceAndSwipeStillWorks() {
        KeyboardAppearance.save(context, KeyboardAppearance(longPressSymbols = false))
        start(); key("按键 q").longClick(); expect("q")
        assertTrue(device.findObjects(By.res("android", "title")).isEmpty())
        val bounds = key("按键 w").visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.top - bounds.height() / 4, 10)
        assertTrue("上滑仍提交一次副符号", device.wait(Until.hasObject(host.text(Pattern.compile(".*2"))), 10000))
        val after = device.findObject(host).text
        SystemClock.sleep(300)
        expect(after)
    }
    @Test fun landscapeMenuKeepsEdgeLettersAndDoesNotChangeShift() {
        device.setOrientationLeft()
        start(); key("按键 ⇧").click()
        open("p"); shot("landscape"); select("p"); expect("p")
        key("按键 a").click(); expect("pA")
        open("l"); select("L"); expect("pAL")
    }
}
