//! 用随包词库与隔离 JNI 会话验证习惯学习确实改变候选排名，并在重建后保留。
package dev.qingstudy.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HabitRankingEffectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tables = listOf(
        "frequency.tsv", "user-words.tsv", "user-ngram.tsv", "user-choices.tsv",
        "user-english.tsv", "user-typos.tsv", "vocabulary.tsv", "usage.tsv"
    )

    @Test fun repeatedHomophoneChoiceImprovesRankSurvivesReopenAndHonorsPrivacy() {
        val root = File(context.cacheDir, "habit-ranking-${UUID.randomUUID()}")
        assertTrue("必须新建独立测试目录", root.mkdir())
        val engineFiles = File(root, "engine").apply { check(mkdir()) }
        val learnedFiles = File(root, "learned")
        val controlFiles = File(root, "control")
        val evidence = JSONObject().put("input", "shi").put("target", "式")
        var handle = 0L
        var control = 0L
        try {
            // 使用 APK 的完整基础词库、词级模型和释义，不植入手写权重或替身学习器。
            for (name in listOf(
                "dict.tsv", "glossary-en.tsv", "english.tsv", "glossary-zh.tsv",
                "emoji-zh.tsv", "emoji-en.tsv", "lm.qj", "levels-en.tsv"
            )) {
                context.assets.open("engine/$name").use { input ->
                    File(engineFiles, name).outputStream().use(input::copyTo)
                }
            }
            handle = open(engineFiles, learnedFiles)
            control = open(engineFiles, controlFiles)
            val before = rank(query(handle), "式")
            evidence.put("beforeRank", before)
            assertTrue("必须从非首位同音候选开始，不能以原首选伪证排序改善", before > 0)
            assertEquals("独立未学习会话应有相同基线", before, rank(query(control), "式"))
            val rounds = JSONArray()
            repeat(3) {
                select(handle, "式")
                rounds.put(rank(query(handle), "式"))
            }
            val learned = rank(query(handle), "式")
            evidence.put("roundRanks", rounds).put("learnedRank", learned)
            assertTrue("重复选词后名次应提升：$before -> $learned", learned < before)
            assertEquals("未学习的控制会话不应受其他会话影响", before, rank(query(control), "式"))
            assertTrue("词频必须实际落盘", File(learnedFiles, "frequency.tsv").isFile)
            assertTrue("同拼音选择偏好必须实际落盘", File(learnedFiles, "user-choices.tsv").isFile)

            NativeBridge.close(handle)
            handle = 0L
            handle = open(engineFiles, learnedFiles)
            val reopened = rank(query(handle), "式")
            evidence.put("reopenedRank", reopened)
            assertEquals("新建 JNI 会话必须读取保存后的候选名次", learned, reopened)
            assertTrue("重建后的候选不能退回未学习基线", reopened < before)

            val boundaries = JSONArray()
            for ((label, enabled, privateInput) in listOf(
                Triple("learning-disabled", false, false),
                Triple("private-editor", true, true)
            )) {
                configure(handle, learnedFiles, enabled, privateInput)
                // 开关切换前查询积累的按键统计先落盘，不把旧的待写记录误判为私密输入新增。
                step(handle, "flush")
                val unchanged = snapshot(learnedFiles)
                repeat(3) { select(handle, "时") }
                step(handle, "flush")
                assertEquals("$label 必须保持八张记录表逐字节不变", unchanged, snapshot(learnedFiles))
                assertEquals("$label 应继续使用已有排序", reopened, rank(query(handle), "式"))
                boundaries.put(JSONObject().put("case", label).put("allTablesUnchanged", true))
            }
            evidence.put("beforeRank", before).put("roundRanks", rounds)
                .put("learnedRank", learned).put("reopenedRank", reopened)
                .put("controlRank", rank(query(control), "式")).put("boundaries", boundaries)
                .put("passed", true)
        } catch (error: Throwable) {
            evidence.put("passed", false).put("error", error.toString())
            throw error
        } finally {
            try {
                if (handle != 0L) NativeBridge.close(handle)
                if (control != 0L) NativeBridge.close(control)
                File(context.getExternalFilesDir(null) ?: context.cacheDir, "habit-ranking-effect.json")
                    .writeText(evidence.toString(2))
            } finally {
                // 只删本次新建且经绝对路径校验的缓存，不接触正式学习目录和偏好设置。
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(root.name.startsWith("habit-ranking-"))
                check(root.deleteRecursively()) { "隔离测试缓存清理失败" }
            }
        }
    }

    private fun open(engineFiles: File, learningFiles: File): Long {
        val handle = NativeBridge.create(engineFiles.absolutePath)
        check(handle != 0L) { "JNI 会话未创建" }
        try { configure(handle, learningFiles, true, false) }
        catch (error: Throwable) { NativeBridge.close(handle); throw error }
        return handle
    }

    private fun configure(handle: Long, directory: File, enabled: Boolean, privateInput: Boolean) {
        val settings = JSONObject().put("directory", directory.absolutePath)
            .put("learning", enabled).put("habitLearning", true).put("private", privateInput)
            .put("domains", JSONArray()).put("emoji", false)
        step(handle, "configure", settings.toString())
    }

    private fun step(handle: Long, action: String, argument: String = ""): JSONObject =
        JSONObject(NativeBridge.step(handle, action, argument))

    private fun query(handle: Long): JSONObject {
        // 每轮回到句首，排除上一轮已提交文本带来的即时上下文偏好。
        step(handle, "reset")
        return step(handle, "input", "shi")
    }

    private fun rank(frame: JSONObject, word: String): Int {
        val candidates = frame.getJSONArray("candidates")
        val index = (0 until candidates.length()).indexOfFirst {
            candidates.getJSONObject(it).getString("text") == word
        }
        assertTrue("随包词库的 shi 候选必须包含 $word", index >= 0)
        return index
    }

    private fun select(handle: Long, word: String) {
        val selected = rank(query(handle), word)
        step(handle, "displayed", JSONArray().put(selected).toString())
        val frame = step(handle, "select", selected.toString())
        assertEquals("必须通过实际候选提交路径学习", word, frame.getString("commit"))
        assertEquals("同音字应消耗完整拼音", "", frame.getString("input"))
    }

    private fun snapshot(directory: File): Map<String, String?> = tables.associateWith { name ->
        val file = File(directory, name)
        if (!file.exists()) null else MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
