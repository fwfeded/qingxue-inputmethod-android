//! 用户原词在两种模式下的真实触屏回放，保存复现并验证切换恢复。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

class PhoneFeedbackTest {
    @Test fun originalWordsAndModeRecovery() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val saved = Preferences.read(context)
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        fun key(label: String) = device.wait(Until.findObject(By.desc(label).enabled(true)), 20000) ?: error("缺少 $label")
        fun candidate(word: String) = By.desc(Pattern.compile("候选 ${Pattern.quote(word)}，.*")).enabled(true)
        fun shot(name: String) = device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-$name.png"))
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val host = By.res("dev.qingstudy.ime", "test_input")
            device.wait(Until.findObject(host), 15000)!!.click()
            key("按键 空格")
            for ((input, expected) in listOf("baqianli" to "八千里", "wanshang" to "晚上", "wanshangwomenyiqichifan" to "晚上我们一起吃饭")) {
                input.forEach { key("按键 $it").click() }
                assertTrue("中文模式：$input", device.wait(Until.hasObject(candidate(expected)), 15000))
                shot("chinese-$input")
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
                key("工具 english").click()
                input.forEach { key("按键 $it").click() }
                assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text(input)), 10000))
                assertTrue(device.findObjects(By.desc(Pattern.compile("候选 .*"))).isEmpty())
                shot("english-$input")
                key("工具 english").click()
                assertTrue("切回中文应直接恢复 $expected", device.wait(Until.hasObject(candidate(expected)), 10000))
                key("按键 空格").click()
                assertTrue(device.wait(Until.hasObject(By.res("dev.qingstudy.ime", "test_input").text(expected)), 10000))
                device.findObject(host)!!.text = ""
            }
            "nihao".forEach { key("按键 $it").click() }
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*"))), 10000))
            shot("gloss")
        } finally { Preferences.save(context, saved) }
    }
}
