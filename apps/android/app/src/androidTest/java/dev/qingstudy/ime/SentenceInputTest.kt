// 用真实外部编辑器验证整句、改错与快速实体按键，不以主机查询代替安卓上屏。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SentenceInputTest {
    @Test fun longSentencesRapidKeysAndCorrectionCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val saved = Preferences.read(context)
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            for ((pinyin, expected) in listOf(
                "mingtianzaoshangjiudianjian" to "明天早上九点见",
                "wozhengzaikaifayigeanzhuoyingyong" to "我正在开发一个安卓应用",
                "qingbangwodakaidiannao" to "请帮我打开电脑"
            )) {
                device.executeShellCommand("am start -W -f 0x10008000 -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode text")
                device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
                device.findObject(UiSelector().description("手机测试输入框")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
                device.executeShellCommand("input text $pinyin")
                assertTrue("整句候选：$expected", device.wait(Until.hasObject(By.desc(Pattern.compile("候选 ${Pattern.quote(expected)}，.*")).enabled(true)), 20000))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sentence-${pinyin.take(8)}.png"))
                device.executeShellCommand("input keyevent 62")
                assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text(expected)), 10000))
            }
            // 触屏删除错误尾字母后继续组合，不能提前上屏或丢掉前面的长拼音。
            device.executeShellCommand("input text womenmingtianjiax")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("请帮我打开电脑womenmingtianjiax")), 10000))
            device.wait(Until.findObject(By.desc("按键 ⌫").enabled(true)), 10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("请帮我打开电脑womenmingtianjia")), 10000))
            device.executeShellCommand("input text n")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("请帮我打开电脑womenmingtianjian")), 10000))
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 我们明天见，.*")).enabled(true)), 15000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "sentence-correction-before-space.png"))
            device.executeShellCommand("input keyevent 62")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("请帮我打开电脑我们明天见")), 10000))
        } finally { Preferences.save(context, saved) }
    }
}
