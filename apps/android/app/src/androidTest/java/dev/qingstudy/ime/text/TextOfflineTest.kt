//! 真模型验收：应用独立服务推理，记录实际耗时并验证取消和进程释放；不以样例回放代替模型。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TextOfflineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    @Test fun cancelingAnOlderClientDoesNotCancelTheNewRequest() {
        assertTrue(TextModels.ready(context))
        var old: TextClient? = null; var latest: TextClient? = null
        val done = CountDownLatch(1); var answer: JSONObject? = null
        try {
            inst.runOnMainSync { old = TextClient(context) { }.also { it.run("明天不要上线".repeat(50)) } }
            SystemClock.sleep(500)
            inst.runOnMainSync {
                latest = TextClient(context) { answer = it; done.countDown() }.also { it.run("明天下午三点在东门开会不要迟到") }
            }
            SystemClock.sleep(300)
            inst.runOnMainSync { old?.close() }
            assertTrue(done.await(90, TimeUnit.SECONDS)); assertFalse(answer.toString(), answer!!.has("error"))
            assertTrue(answer!!.getString("text").contains("不要迟到"))
        } finally { inst.runOnMainSync { old?.close(); latest?.close() } }
    }
    @Before fun prepareModel() {
        if (!TextModels.ready(context)) {
            val fixture = File("/data/local/tmp/qingstudy-text-lab/${TextModels.spec.name}")
            assertTrue("需保留已下载真实模型", VoiceModelTransfer.verify(fixture, TextModels.spec))
            fixture.copyTo(TextModels.model(context), overwrite = true)
            assertTrue(VoiceModelTransfer.verify(TextModels.model(context), TextModels.spec))
            File(TextModels.directory(context), "verified").writeText(TextModels.spec.sha256)
        }
    }
    @Test fun realModelRunsOfflineAndCancelReleasesProcess() {
        val device = UiDevice.getInstance(inst)
        val wifi = device.executeShellCommand("settings get global wifi_on").trim()
        val mobile = device.executeShellCommand("settings get global mobile_data").trim()
        val rows = JSONArray()
        var client: TextClient? = null
        try {
            device.executeShellCommand("svc wifi disable"); device.executeShellCommand("svc data disable")
            for (source in listOf("这笔钱不是八千是一万八千元不要今天转账等合同签完再转", "明天下午三点在东门开会不要迟到")) {
                val done = CountDownLatch(1); var answer: JSONObject? = null
                inst.runOnMainSync { client = TextClient(context) { answer = it; done.countDown() }.also { it.run(source) } }
                assertTrue("真实推理超时", done.await(120, TimeUnit.SECONDS))
                assertFalse(answer.toString(), answer!!.has("error")); assertTrue(answer!!.getString("text").isNotBlank())
                rows.put(answer!!.put("source", source))
            }
            var callback = false
            inst.runOnMainSync { client = TextClient(context) { callback = true }.also { it.run("明天不要上线".repeat(60)) } }
            SystemClock.sleep(400)
            val cancelAt = SystemClock.elapsedRealtime()
            inst.runOnMainSync { client!!.close() }
            val until = SystemClock.elapsedRealtime() + 8000
            while (device.executeShellCommand("pidof dev.qingstudy.ime:text").trim().isNotEmpty() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertFalse(callback); assertTrue(device.executeShellCommand("pidof dev.qingstudy.ime:text").trim().isEmpty())
            File(context.getExternalFilesDir(null), "text-runtime-evidence.json").writeText(JSONObject().put("cases", rows).put("cancel_release_ms", SystemClock.elapsedRealtime() - cancelAt).put("wifi_off", true).toString(2))
        } finally {
            inst.runOnMainSync { client?.close() }
            device.executeShellCommand("svc wifi ${if (wifi in listOf("1", "2")) "enable" else "disable"}")
            device.executeShellCommand("svc data ${if (mobile == "1") "enable" else "disable"}")
        }
    }
}
