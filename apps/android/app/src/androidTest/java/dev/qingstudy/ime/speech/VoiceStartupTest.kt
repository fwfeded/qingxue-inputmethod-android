//! 冷启动必须先开始真实采集，再完成模型加载；同一固定音频记录端到端时间。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceStartupTest {
    @Test fun coldCaptureStartsBeforeModelFinishesLoading() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        assertTrue(device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim().isEmpty())
        VoiceModels.prepare(context)
        val done = CountDownLatch(1)
        val result = JSONObject()
        val began = SystemClock.elapsedRealtime()
        lateinit var client: VoiceClient
        inst.runOnMainSync {
            client = VoiceClient(context, object : VoiceClient.Listener {
                override fun onState(state: String, message: String) {
                    if (state == "listening" && !result.has("listeningMs")) result.put("listeningMs", SystemClock.elapsedRealtime() - began)
                    if (state == "error") { result.put("error", message); done.countDown() }
                }
                override fun onMetrics(loadMs: Long, reused: Boolean) {
                    result.put("loadMs", loadMs).put("modelReadyMs", SystemClock.elapsedRealtime() - began).put("reused", reused)
                }
                override fun onPartial(text: String) { if (!result.has("firstTextMs")) result.put("firstTextMs", SystemClock.elapsedRealtime() - began) }
                override fun onResult(text: String, decodeMs: Long) {
                    result.put("text", text).put("decodeMs", decodeMs).put("finalMs", SystemClock.elapsedRealtime() - began); done.countDown()
                }
            })
            client.startFixtureForTest()
        }
        try {
            assertTrue("语音结果超时", done.await(40, TimeUnit.SECONDS))
            assertFalse(result.toString(), result.has("error"))
            assertTrue(result.getString("text").contains("你好"))
            assertFalse(result.getBoolean("reused"))
            assertTrue("录音不应等待模型：$result", result.getLong("listeningMs") < result.getLong("modelReadyMs"))
        } finally {
            File(context.getExternalFilesDir(null), "voice-startup.json").writeText(result.toString(2))
            inst.runOnMainSync { client.release(); client.close() }
        }
    }
}
