package dev.qingstudy.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VoicePerformanceTest {
    @Test fun realFixtureColdWarmAndIdleExit() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        assertTrue("性能实验需从无语音进程启动", device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim().isEmpty())
        // 可选包已由独立下载实验校验；性能实验只测冷进程加载，不重新下载。
        assertTrue("请先下载或显式导入真实离线包", VoiceModels.isReady(context))
        val modelStart = android.os.SystemClock.elapsedRealtime()
        VoiceModels.prepare(context)
        val output = JSONObject().put("prepareMs", android.os.SystemClock.elapsedRealtime() - modelStart).put("firstPreparation", false)
        val runs = JSONArray()
        lateinit var client: VoiceClient
        val done = AtomicReference<CountDownLatch>()
        val current = AtomicReference<JSONObject>()
        val failure = AtomicReference<String>()
        inst.runOnMainSync {
            client = VoiceClient(context, object : VoiceClient.Listener {
                override fun onState(state: String, message: String) { if (state == "error") { failure.set(message); done.get()?.countDown() } }
                override fun onPartial(text: String) { current.get()?.put("partial", true) }
                override fun onResult(text: String, decodeMs: Long) { current.get().put("text", text).put("decodeMs", decodeMs); done.get().countDown() }
                override fun onMetrics(loadMs: Long, reused: Boolean) { current.get().put("loadMs", loadMs).put("reused", reused) }
            })
        }
        try {
            for (i in 0..1) {
                current.set(JSONObject()); done.set(CountDownLatch(1))
                inst.runOnMainSync { client.startFixtureForTest() }
                assertTrue(done.get().await(45, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue(current.get().getString("text").contains("天气"))
                assertEquals(i == 1, current.get().getBoolean("reused"))
                val memory = device.executeShellCommand("dumpsys meminfo dev.qingstudy.ime:voice")
                File(context.getExternalFilesDir(null), "voice-memory-$i.txt").writeText(memory)
                runs.put(current.get())
            }
            output.put("runs", runs)
            val until = android.os.SystemClock.elapsedRealtime() + 68000
            var alive = true
            while (alive && android.os.SystemClock.elapsedRealtime() < until) {
                Thread.sleep(1000)
                alive = device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim().isNotEmpty()
            }
            output.put("idleProcessExited", !alive)
            assertFalse("空闲后不应保留模型进程", alive)
        } finally {
            File(context.getExternalFilesDir(null), "voice-performance.json").writeText(output.toString(2))
            inst.runOnMainSync { client.release(); client.close() }
        }
    }
}
