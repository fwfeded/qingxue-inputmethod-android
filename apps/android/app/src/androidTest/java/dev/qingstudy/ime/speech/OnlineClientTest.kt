//! 正式传输客户端 + 真实电脑模型；故障端点仅测试断线、迟到结果和取消。
package dev.qingstudy.ime

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class OnlineClientTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val base = inst.targetContext
    private lateinit var folder: File
    private lateinit var context: ContextWrapper
    private var client: OnlineSpeechClient? = null
    private val events = LinkedBlockingQueue<Pair<String,String>>()
    @Before fun setup() {
        folder=File(base.cacheDir,"online-test-${System.nanoTime()}").apply { mkdirs() }
        context=object:ContextWrapper(base) { override fun getFilesDir()=folder }
        File(base.filesDir,"debug-voice-input.wav").copyTo(File(folder,"debug-voice-input.wav"))
    }
    @After fun cleanup() { inst.runOnMainSync { client?.close() }; folder.deleteRecursively() }
    private fun start(url: String="ws://10.0.2.2:10095",key:String="test-only-connection-secret") {
        VoiceSettings.saveServer(context,url,key)
        inst.runOnMainSync { client=OnlineSpeechClient(context,object:EmptyVoiceListener() {
            override fun onState(state:String,message:String) { events.add(state to message) }
            override fun onPartial(text:String) { events.add("partial" to text) }
            override fun onResult(text:String,decodeMs:Long) { events.add("final" to text) }
        }).also { it.start(false,true) } }
    }
    private fun await(kind:String):String {
        val end=SystemClock.elapsedRealtime()+20000
        while(SystemClock.elapsedRealtime()<end) {
            val event=events.poll(1,TimeUnit.SECONDS) ?: continue
            if(event.first==kind)return event.second
            if(event.first=="error")fail(event.second)
        }
        error("未收到 $kind")
    }
    @Test fun realModelStreamsAndFinalReplaces() {
        val started=SystemClock.elapsedRealtime();start()
        assertTrue(await("partial").isNotBlank())
        val first=SystemClock.elapsedRealtime()-started
        val result=await("final");assertTrue(result,result.contains("你好"))
        File(base.getExternalFilesDir(null),"online-client-metrics.json").writeText(JSONObject().put("first_ms",first).put("result",result).toString())
    }
    @Test fun badKeyThenFreshConnectionRecovers() {
        start(key="invalid-test-connection-secret")
        assertTrue(await("error").contains("密钥"))
        events.clear();start();assertTrue(await("partial").isNotBlank())
        assertTrue(await("final").contains("你好"))
    }
    @Test fun disconnectedSocketThenFreshSessionRecovers() {
        start("ws://10.0.2.2:10096/drop")
        assertEquals("临时文字",await("partial"));assertTrue(await("error").contains("连接"))
        events.clear();start();assertTrue(await("final").contains("你好"))
    }
    @Test fun cancelledFinalCannotDeliverOldText() {
        start("ws://10.0.2.2:10096/delayed");assertEquals("临时文字",await("partial"))
        inst.runOnMainSync { client!!.stop() };await("processing")
        inst.runOnMainSync { client!!.cancel() };events.clear();SystemClock.sleep(2500)
        assertFalse(events.any { it.first=="final" })
    }
    @Test fun immediateStopCompletesWithoutWaitingForTimeout() {
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10095","test-only-connection-secret")
        val start=SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            client=OnlineSpeechClient(context,object:EmptyVoiceListener() {
                override fun onState(state:String,message:String) { events.add(state to message) }
                override fun onResult(text:String,decodeMs:Long) { events.add("final" to text) }
            }).also { it.start(false,true); it.stop() }
        }
        await("final");assertTrue(SystemClock.elapsedRealtime()-start<3000)
    }
}
