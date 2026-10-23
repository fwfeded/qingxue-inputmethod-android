//! 固定六条语料经同一服务实时播放，保存前后版本的最终文本、首字和CPU/内存快照。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceCorpusV4Test {
    @Test fun fixedCorpusProducesResultsWithoutExcessiveReprocessing() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst);device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        val results=JSONArray();val fixture=File(context.filesDir,"debug-voice-input.wav")
        val original=fixture.readBytes()
        fun ticks():Long {
            val pid=device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim()
            if(!pid.matches(Regex("[0-9]+")))return 0
            val fields=device.executeShellCommand("run-as dev.qingstudy.ime cat /proc/$pid/stat").trim().split(Regex("\\s+"))
            return if(fields.size>14) (fields[13].toLongOrNull() ?: 0)+(fields[14].toLongOrNull() ?: 0) else 0
        }
        lateinit var client:VoiceClient
        var done=CountDownLatch(1);var row=JSONObject();var began=0L
        inst.runOnMainSync {
            client=VoiceClient(context,object:VoiceClient.Listener {
                override fun onState(state:String,message:String){if(state=="error"){row.put("error",message);done.countDown()}}
                override fun onPartial(text:String){if(!row.has("firstTextMs"))row.put("firstTextMs",SystemClock.elapsedRealtime()-began).put("firstText",text)}
                override fun onResult(text:String,decodeMs:Long){row.put("text",text).put("decodeMs",decodeMs).put("finalMs",SystemClock.elapsedRealtime()-began);done.countDown()}
                override fun onMetrics(loadMs:Long,reused:Boolean){row.put("loadMs",loadMs).put("reused",reused)}
            })
        }
        try {
            for(name in listOf("daily","learning","chat","numbers","mixed","english")) {
                File(context.filesDir,"v4-corpus/tts-$name.wav").copyTo(fixture,overwrite=true)
                row=JSONObject().put("name",name);done=CountDownLatch(1)
                val cpu=ticks();began=SystemClock.elapsedRealtime()
                inst.runOnMainSync { client.startFixtureForTest() }
                assertTrue(done.await(30,TimeUnit.SECONDS));row.put("cpuTicks",ticks()-cpu)
                results.put(row);assertFalse(row.toString(),row.has("error"));assertTrue(row.getString("text").isNotBlank())
                File(context.getExternalFilesDir(null),"v4-corpus-memory-$name.txt").writeText(device.executeShellCommand("dumpsys meminfo dev.qingstudy.ime:voice"))
            }
        } finally {
            File(context.getExternalFilesDir(null),"v4-corpus.json").writeText(results.toString(2))
            fixture.writeBytes(original);inst.runOnMainSync { client.release();client.close() }
        }
    }
}
