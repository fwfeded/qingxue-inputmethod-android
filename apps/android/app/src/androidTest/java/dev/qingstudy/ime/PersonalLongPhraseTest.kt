// 设备上的真实随包词库与 JNI：长词重复分选、落盘、重建和英文释义。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PersonalLongPhraseTest {
    @Test fun repeatedSplitPhrasePersistsWithoutChangingGlosses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "personal-long-${UUID.randomUUID()}").apply { check(mkdir()) }
        val assets = File(root, "engine").apply { check(mkdir()) }
        val learned = File(root, "learned")
        for (name in listOf("dict.tsv", "glossary-en.tsv")) {
            context.assets.open("engine/$name").use { input -> File(assets, name).outputStream().use(input::copyTo) }
        }
        var handle = NativeBridge.create(assets.absolutePath)
        assertTrue(handle != 0L)
        fun step(action: String, value: String = "") = JSONObject(NativeBridge.step(handle, action, value)).also { assertFalse(it.toString(), it.has("error")) }
        fun configure() { step("configure", JSONObject().put("directory", learned.absolutePath).put("learning", true).toString()) }
        fun choose(frame: JSONObject, word: String): JSONObject {
            val items = frame.getJSONArray("candidates")
            val i = (0 until items.length()).first { items.getJSONObject(it).getString("text") == word }
            return step("select", i.toString())
        }
        try {
            configure()
            repeat(2) {
                step("reset")
                val remainder = choose(step("input", "gaoxiaoshurufa"), "高效")
                assertEquals("shurufa", remainder.getString("input"))
                assertEquals("输入法", choose(remainder, "输入法").getString("commit"))
            }
            assertTrue(File(learned, "user-words.tsv").readText().contains("高效输入法"))
            NativeBridge.close(handle); handle = NativeBridge.create(assets.absolutePath); configure()
            val frame = step("input", "gaoxiaoshurufa")
            assertEquals("高效输入法", frame.getJSONArray("candidates").getJSONObject(0).getString("text"))
            step("reset")
            val greeting = step("input", "nihao").getJSONArray("candidates")
            assertTrue((0 until greeting.length()).any {
                greeting.getJSONObject(it).getString("text") == "你好" &&
                    greeting.getJSONObject(it).getString("gloss").contains("hello")
            })
        } finally { if (handle != 0L) NativeBridge.close(handle) }
    }
}
