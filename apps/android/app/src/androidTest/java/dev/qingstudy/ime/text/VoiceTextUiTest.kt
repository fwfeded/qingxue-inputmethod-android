//! 真实语音编辑调用整理，采用前宿主不变，撤回恢复，返回键盘仍有中文及英文释义。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoiceTextUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private fun key(desc: String) = device.wait(Until.findObject(By.desc(desc).enabled(true)), 30000) ?: error("缺少 $desc")
    private fun reveal(desc: String): UiObject2 {
        device.waitForIdle()
        device.wait(Until.findObject(By.desc(desc)), 1500)
        if (device.findObject(By.desc(desc))?.visibleBounds?.isEmpty != false) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(desc))
        }
        device.waitForIdle(); SystemClock.sleep(500)
        return key(desc)
    }
    @Test fun organizeVoiceDraftThenUndoAndTypeWithGloss() {
        assertTrue(TextModels.ready(context)); assertTrue(VoiceModels.isReady(context))
        val saved = VoiceSettings.read(context).toString()
        val fixture = File(context.filesDir, "debug-voice-enabled")
        val old = if (fixture.exists()) fixture.readBytes() else null
        try {
            VoiceSettings.save(context, "provider", "offline"); VoiceSettings.save(context, "entry", true)
            fixture.writeText("text integration test")
            device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
            device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
            // 上一 instrumentation 结束会强停被测包；切换到系统 IME 再回来建立新绑定。
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val host = By.res("dev.qingstudy.ime", "test_input")
            device.wait(Until.findObject(host), 10000)!!.click()
            key("按键 空格")
            key("工具 voice").click()
            key("语音 pause").click()
            assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续")), 30000))
            val source = "明天下午三点在东门开会不要迟到"
            key("语音编辑文字").text = source
            key("语音 organize").click()
            assertTrue(device.wait(Until.hasObject(By.desc("整理原文")), 5000))
            reveal("开始整理").click()
            reveal("整理结果")
            assertTrue(device.wait(Until.hasObject(By.desc("采用结果").enabled(true)), 120000))
            reveal("采用结果").click()
            reveal("撤回采用").click()
            assertEquals(source, reveal("整理原文").text)
            device.findObject(By.text("返回语音编辑")).click()
            assertTrue(device.wait(Until.gone(By.desc("整理原文")), 5000))
            assertEquals(source, device.wait(Until.findObject(host), 5000)!!.text)
            key("语音 finish").click()
            "nihao".forEach { key("按键 $it").click() }
            assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*"))), 15000))
            key("按键 空格").click()
            assertEquals(source + "你好", device.findObject(host).text)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "text-voice-gloss.png"))
        } catch (failure: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "text-voice-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "text-voice-failure.xml"))
            throw failure
        } finally {
            if (old == null) fixture.delete() else fixture.writeBytes(old)
            File(context.filesDir, "voice-settings.json").writeText(saved)
            device.pressBack()
        }
    }
}
