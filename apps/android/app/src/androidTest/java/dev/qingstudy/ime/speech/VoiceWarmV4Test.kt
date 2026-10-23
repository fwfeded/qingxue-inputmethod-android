//! 预热不得启动话筒；预热中的真实采集不能排在加载后丢开头。
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

class VoiceWarmV4Test {
    @Test fun preparationIsSilentAndNextDictationReusesModel() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val ready=CountDownLatch(1);val done=CountDownLatch(1);val output=JSONObject()
        var recordingEvents=0;var began=0L
        lateinit var client: VoiceClient
        inst.runOnMainSync {
            client=VoiceClient(context,object:VoiceClient.Listener {
                override fun onWarmReady(loadMs: Long) { output.put("prepareMs",loadMs);ready.countDown() }
                override fun onState(state: String,message: String) { if(state=="listening")recordingEvents++;if(state=="error"){output.put("error",message);done.countDown()} }
                override fun onLevel(level: Float) { if(level>0)recordingEvents++ }
                override fun onPartial(text: String) { if(!output.has("firstTextMs"))output.put("firstTextMs",SystemClock.elapsedRealtime()-began) }
                override fun onResult(text: String,decodeMs: Long) { output.put("text",text).put("decodeMs",decodeMs);done.countDown() }
                override fun onMetrics(loadMs: Long,reused: Boolean) { output.put("loadMs",loadMs).put("reused",reused) }
            });client.warmUp()
        }
        try {
            assertTrue(ready.await(20,TimeUnit.SECONDS));assertEquals("预热不得录音",0,recordingEvents)
            File(context.getExternalFilesDir(null),"v4-warm-memory.txt").writeText(UiDevice.getInstance(inst).executeShellCommand("dumpsys meminfo dev.qingstudy.ime:voice"))
            began=SystemClock.elapsedRealtime();inst.runOnMainSync { client.startFixtureForTest() }
            assertTrue(done.await(20,TimeUnit.SECONDS));assertFalse(output.toString(),output.has("error"))
            assertTrue(output.getBoolean("reused"));assertTrue(output.getLong("loadMs")<100)
            assertTrue(output.toString(),output.getLong("firstTextMs")<2500)
            assertTrue(output.getString("text").contains("你好"))
        } finally {
            File(context.getExternalFilesDir(null),"v4-warm.json").writeText(output.toString(2))
            inst.runOnMainSync { client.release();client.close() }
        }
    }
}
