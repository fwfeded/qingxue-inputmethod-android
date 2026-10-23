//! 通过实际 IME 入口验证暂停/继续保留离线模型，避免只测独立客户端漏掉控制器重绑。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceImeReuseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val host get() = By.res("dev.qingstudy.ime", "test_input")

    @Test fun imePreparationThenContinueKeepsTheLoadedModelAndAcceptsNewText() {
        assertTrue("需已下载并验证的真实离线包", VoiceModels.isReady(context))
        assertTrue("需真实 PCM16/16000 WAV", File(context.filesDir, "debug-voice-input.wav").isFile)
        val settings = VoiceSettings.read(context).toString()
        val marker = File(context.filesDir, "debug-voice-enabled")
        val oldMarker = marker.takeIf { it.isFile }?.readBytes()
        val output = JSONObject()
        try {
            VoiceSettings.save(context, "provider", "offline")
            VoiceSettings.save(context, "entry", true)
            VoiceSettings.save(context, "send_command", false)
            marker.writeText("IME warm-reuse test")
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
            device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            await("测试起点必须是冷语音进程", 15_000) { voicePid().isEmpty() }
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val editor = device.wait(Until.findObject(host), 15_000) ?: error("未找到宿主输入框")
            editor.click()
            output.put("phase", "await-keyboard")
            assertTrue("宿主输入框应先获得焦点", device.wait(Until.hasObject(host.focused(true)), 15_000))
            // 工具栏在词库冷加载时已显示；等可用按键和窗口稳定后再启动语音。
            key("按键 空格")
            device.waitForIdle()
            val priorMetrics = metrics().size
            output.put("phase", "await-voice-panel").put("priorMetrics", priorMetrics)
            key("工具 voice").click()
            assertTrue("语音入口点击后必须出现语音层", device.wait(Until.hasObject(By.desc("语音状态")), 10_000))
            output.put("phase", "await-cold-metrics")
            await("冷启动模型指标未返回", 30_000) { metrics().size > priorMetrics }
            val cold = metrics()[priorMetrics]
            // V4 键盘显示后可能已完成无录音准备；首次点击不再要求重新加载模型。
            output.put("initial", cold)
            val firstPid = voicePid()
            output.put("initialPid", firstPid)
            assertTrue(firstPid.isNotEmpty())
            await("第一段真实音频未写入宿主", 35_000) { text().isNotBlank() }
            val pause = key("语音 pause")
            if (pause.text == "暂停") pause.click()
            assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续").enabled(true)), 20_000))
            val firstText = text()
            output.put("firstText", firstText)
            assertTrue(firstText.isNotBlank())
            val beforeContinue = metrics().size
            key("语音 pause").click()
            output.put("phase", "await-warm-metrics")
            await("继续后的模型指标未返回", 30_000) { metrics().size > beforeContinue }
            val warm = metrics()[beforeContinue]
            output.put("warm", warm).put("warmPid", voicePid())
            assertTrue("IME 暂停后继续不得重载模型：$warm", warm.getBoolean("reused"))
            assertEquals("暂停/继续必须保留同一语音进程", firstPid, voicePid())
            await("新录音代号的文字未追加到原结果", 35_000) { text().startsWith(firstText) && text().length > firstText.length }
            output.put("continuedText", text())
            val secondPause = key("语音 pause")
            if (secondPause.text == "暂停") secondPause.click()
            assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续").enabled(true)), 20_000))
            output.put("phase", "complete")
        } finally {
            output.put("lastState", device.findObject(By.desc("语音状态"))?.text ?: "absent")
            File(context.getExternalFilesDir(null), "voice-ime-reuse.json").writeText(output.toString(2))
            device.pressBack()
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            File(context.filesDir, "voice-settings.json").writeText(settings)
            if (oldMarker == null) marker.delete() else marker.writeBytes(oldMarker)
        }
    }

    private fun key(description: String) = device.wait(Until.findObject(By.desc(description).enabled(true)), 20_000)
        ?: error("缺少 $description")

    private fun text(): String = device.findObject(host)?.text.orEmpty().takeUnless { it == "点击这里开始输入" }.orEmpty()

    private fun voicePid() = device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim()

    private fun metrics(): List<JSONObject> {
        val pid = device.executeShellCommand("pidof dev.qingstudy.ime:ime").trim().toIntOrNull() ?: return emptyList()
        val log = device.executeShellCommand("logcat -d -v brief --pid $pid QingVoiceUI:I *:S")
        return Regex("loadMs=(\\d+) reused=(true|false)").findAll(log).map {
            JSONObject().put("loadMs", it.groupValues[1].toLong()).put("reused", it.groupValues[2].toBoolean())
        }.toList()
    }

    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        assertTrue("$message；状态=${device.findObject(By.desc("语音状态"))?.text}", condition())
    }
}
