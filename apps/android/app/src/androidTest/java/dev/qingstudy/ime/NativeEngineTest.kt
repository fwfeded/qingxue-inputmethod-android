// 在安卓进程中验证 JNI、真实数据、候选提交和句柄隔离。
package dev.qingstudy.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    @Test fun realEngineRunsThroughJni() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-test").apply { mkdirs() }
        for (name in listOf("dict.tsv", "glossary-en.tsv")) {
            context.assets.open("engine/$name").use { input -> File(directory, name).outputStream().use(input::copyTo) }
        }
        val handle = NativeBridge.create(directory.absolutePath)
        val evidence = JSONArray()
        try {
            for ((pinyin, chinese) in listOf("nihao" to "你好", "kaifa" to "开发", "yingyu" to "英语")) {
                NativeBridge.step(handle, "reset", "")
                val raw = NativeBridge.step(handle, "input", pinyin)
                var frame = Frame.parse(raw)
                val index = frame.candidates.indexOfFirst { it.first == chinese }
                assertTrue("$pinyin 应出现 $chinese", index >= 0)
                assertTrue("$chinese 应有英文释义", frame.candidates[index].second.isNotBlank())
                evidence.put(JSONObject().put("pinyin", pinyin).put("candidate", chinese)
                    .put("gloss", frame.candidates[index].second).put("queryMs", JSONObject(raw).getDouble("elapsedMs")))
                frame = Frame.parse(NativeBridge.step(handle, "select", index.toString()))
                assertEquals(chinese, frame.commit)
                assertEquals("", frame.input)
            }
            NativeBridge.step(handle, "input", "nihaoshijie")
            var frame = Frame.parse(NativeBridge.step(handle, "query", ""))
            val index = frame.candidates.indexOfFirst { it.first == "你好" }
            assertTrue(index >= 0)
            frame = Frame.parse(NativeBridge.step(handle, "select", index.toString()))
            assertEquals("你好", frame.commit)
            assertEquals("shijie", frame.input)
            frame = Frame.parse(NativeBridge.step(handle, "delete", ""))
            assertEquals("shiji", frame.input)
            NativeBridge.step(handle, "reset", "")
            assertTrue(Frame.parse(NativeBridge.step(handle, "delete", "")).delete)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val rejected = executor.submit<Boolean> {
                    runCatching { NativeBridge.step(handle, "query", "") }.exceptionOrNull() is IllegalStateException
                }.get()
                assertTrue("跨线程句柄必须被拒绝", rejected)
            } finally { executor.shutdown() }
        } finally { NativeBridge.close(handle) }
        assertTrue(runCatching { NativeBridge.step(handle, "query", "") }.exceptionOrNull() is IllegalStateException)
        File(context.getExternalFilesDir(null), "native-validation.json").writeText(evidence.toString(2))
    }
}
