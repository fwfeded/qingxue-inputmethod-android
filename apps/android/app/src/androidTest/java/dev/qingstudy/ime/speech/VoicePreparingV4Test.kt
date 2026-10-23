//! 在预热尚未完成时开始固定音源，确保采集不受模型线程阻塞。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoicePreparingV4Test {
    @Test fun captureStartsWhileWarmUpIsStillRunning() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst)
        assertTrue(device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim().isEmpty())
        val done=CountDownLatch(1);val result=JSONObject();val began=SystemClock.elapsedRealtime()
        lateinit var client:VoiceClient
        inst.runOnMainSync {
            client=VoiceClient(context,object:VoiceClient.Listener {
                override fun onWarmReady(loadMs:Long) { result.put("warmReadyMs",SystemClock.elapsedRealtime()-began) }
                override fun onState(state:String,message:String) {
                    if(state=="listening")result.put("listeningMs",SystemClock.elapsedRealtime()-began)
                    if(state=="error"){result.put("error",message);done.countDown()}
                }
                override fun onPartial(text:String) = Unit
                override fun onMetrics(loadMs:Long,reused:Boolean) { result.put("loadMs",loadMs).put("reused",reused) }
                override fun onResult(text:String,decodeMs:Long) { result.put("text",text);done.countDown() }
            });client.warmUp()
        }
        try {
            // 留出跨进程绑定时间，让 WARM 先进入 owner；仍在冷模型初始化期间启动采集。
            SystemClock.sleep(500)
            inst.runOnMainSync { client.startFixtureForTest() }
            assertTrue(done.await(30,TimeUnit.SECONDS));assertFalse(result.toString(),result.has("error"))
            assertTrue(result.toString(),result.getLong("listeningMs")<result.getLong("warmReadyMs"))
            assertTrue(result.getBoolean("reused"));assertTrue(result.getString("text").startsWith("你好"))
        } finally {
            File(context.getExternalFilesDir(null),"v4-preparing.json").writeText(result.toString(2))
            inst.runOnMainSync { client.release();client.close() }
        }
    }
}
