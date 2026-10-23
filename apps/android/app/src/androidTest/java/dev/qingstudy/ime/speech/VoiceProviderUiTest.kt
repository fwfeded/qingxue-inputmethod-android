//! 系统服务缺失是真实AVD路径，来源选择/能力提示通过正式设置页验证。
package dev.qingstudy.ime

import android.content.Intent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.*
import org.junit.Assert.*

class VoiceProviderUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private lateinit var original: String
    @Before fun setup() {
        original = VoiceSettings.read(context).toString()
        File(context.filesDir, "debug-voice-enabled").delete()
        VoiceSettings.save(context, "provider", "system")
        VoiceSettings.save(context, "entry", true)
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun cleanup() {
        device.pressBack()
        File(context.filesDir, "voice-settings.json").writeText(original)
    }
    private fun scroll(text: String) {
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text)))
        device.waitForIdle(); SystemClock.sleep(300)
    }
    private fun saved(key: String, value: String) {
        val end = SystemClock.uptimeMillis() + 5000
        while (VoiceSettings.read(context).optString(key) != value && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        assertEquals(value, VoiceSettings.read(context).optString(key))
        device.waitForIdle()
    }
    private fun shot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "r5-$name.png")) }
    @Test fun unavailableSystemServiceReportsErrorWithoutOfflineFallback() {
        Assume.assumeFalse(SpeechRecognizer.isRecognitionAvailable(context))
        context.startActivity(Intent(context, TryInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val field = device.wait(Until.findObject(By.res("dev.qingstudy.ime", "test_input")), 10000)!!
        field.click()
        device.wait(Until.findObject(By.desc("工具 voice").enabled(true)), 15000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音状态").textContains("手机未提供语音服务")), 5000))
        saved("provider", "system")
        assertFalse(field.text.orEmpty().contains("天气"))
        shot("system-unavailable")
        device.findObjects(By.desc("语音 keyboard")).last().click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 5000))
        assertNotNull(device.wait(Until.findObject(By.desc("按键 空格")), 5000))
    }
    @Test fun settingsExposeSourceCapabilitiesAndRealPackageState() {
        context.startActivity(Intent(context, VoiceSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("离线语音 · 此手机")), 10000)!!.click()
        saved("provider", "offline")
        scroll("标点符号"); device.findObject(By.text("标点符号")).click()
        assertFalse(device.wait(Until.findObject(By.text("智能添加标点（模型）")), 5000)!!.isEnabled)
        device.findObject(By.text("取消")).click()
        scroll("手机语音服务（不可用）")
        assertFalse(device.findObject(By.text("手机语音服务（不可用）")).isEnabled)
        saved("provider", "offline")
        shot("source-settings")
        scroll("使用蓝牙耳机麦克风")
        assertTrue(device.findObject(By.text("使用蓝牙耳机麦克风")).isEnabled)
        scroll("离线语音包"); device.findObject(By.text("离线语音包")).click()
        assertNotNull(device.wait(Until.findObject(By.text("删除离线语音包")), 5000))
        assertEquals("已就绪", device.findObject(By.desc("离线包状态")).text)
        shot("model-ready")
    }
}
