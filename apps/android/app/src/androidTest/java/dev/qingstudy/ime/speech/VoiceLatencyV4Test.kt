//! 同一合成音频的首字与最后结果基准，记录进程 CPU 和内存而非只改阈值。
package dev.qingstudy.ime

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceLatencyV4Test {
    @Test fun firstPartialArrivesBeforeThreeAndHalfSeconds() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val result = JSONObject()
        val done = CountDownLatch(1)
        val began = SystemClock.elapsedRealtime()
        lateinit var client: VoiceClient
        inst.runOnMainSync {
            client = VoiceClient(context, object : VoiceClient.Listener {
                override fun onState(state: String, message: String) { if (state == "error") { result.put("error",message); done.countDown() } }
                override fun onPartial(text: String) { if (!result.has("firstTextMs")) result.put("firstTextMs",SystemClock.elapsedRealtime()-began).put("firstText",text) }
                override fun onResult(text: String, decodeMs: Long) { result.put("text",text).put("decodeMs",decodeMs).put("finalMs",SystemClock.elapsedRealtime()-began); done.countDown() }
                override fun onMetrics(loadMs: Long, reused: Boolean) { result.put("loadMs",loadMs).put("reused",reused) }
            })
            client.startFixtureForTest()
        }
        try {
            assertTrue(done.await(35,TimeUnit.SECONDS))
            assertFalse(result.toString(),result.has("error"))
            assertTrue(result.getString("text").contains("散步"))
            assertTrue("首字仍等待四秒：$result",result.getLong("firstTextMs")<3500)
        } finally {
            val device=UiDevice.getInstance(inst)
            File(context.getExternalFilesDir(null),"v4-latency.json").writeText(result.toString(2))
            File(context.getExternalFilesDir(null),"v4-memory.txt").writeText(device.executeShellCommand("dumpsys meminfo dev.qingstudy.ime:voice"))
            inst.runOnMainSync { client.release(); client.close() }
        }
    }
}
