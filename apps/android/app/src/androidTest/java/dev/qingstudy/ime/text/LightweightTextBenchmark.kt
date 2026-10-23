//! 仅显式运行的轻量模型实验；使用正式 JNI 和保护策略，不替换应用模型或改动用户数据。
package dev.qingstudy.ime

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LightweightTextBenchmark {
    private fun pss(): Long = File("/proc/self/smaps_rollup").useLines { lines ->
        lines.first { it.startsWith("Pss:") }.substringAfter(':').trim().substringBefore(' ').toLong()
    }
    @Test fun compareFixedCases() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅在显式基准实验中运行", args.containsKey("bench_model"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val name = args.getString("bench_model")!!
        val path = args.getString("bench_path")!!
        require(path.startsWith("/data/local/tmp/qingstudy-text-lab/"))
        require(File(path).isFile)
        val round = args.getString("bench_round", "0")!!.toInt()
        val input = if (args.containsKey("bench_fixtures")) {
            val fixture = args.getString("bench_fixtures")!!
            require(fixture.startsWith("/data/local/tmp/qingstudy-text-lab/bench-"))
            JSONArray(File(fixture).readText())
        } else JSONArray(inst.context.assets.open(args.getString("bench_cases", "text-lightweight-cases.json")!!).bufferedReader().readText())
        val rows = JSONArray()
        val result = JSONObject().put("model", name).put("round", round).put("pid", Process.myPid())
            .put("path", path).put("bytes", File(path).length()).put("pssBaselineKb", pss()).put("cases", rows)
        val output = File(inst.targetContext.getExternalFilesDir(null), "lightweight-$name-$round.json")
        for (index in 0 until input.length()) {
            val case = input.getJSONObject(index)
            if (args.containsKey("bench_ids") && case.getString("id") !in args.getString("bench_ids")!!.split(',')) continue
            if (round > 0 && case.getString("id") !in listOf("o02", "n01", "n11")) continue
            val source = case.getString("text")
            val row = JSONObject(case.toString()).put("pssBeforeKb", pss())
            val running = AtomicBoolean(true)
            val peak = AtomicLong(pss())
            val samplingErrors = AtomicLong(0)
            val monitor = Thread {
                while (running.get()) {
                    try { peak.updateAndGet { old -> maxOf(old, pss()) } } catch (_: Exception) { samplingErrors.incrementAndGet() }
                    SystemClock.sleep(50)
                }
            }.apply { start() }
            var punc: OfflinePunctuation? = null
            val began = SystemClock.elapsedRealtime()
            try {
                val answer = if (name in listOf("ct-int8", "ct-clean", "ct-qwen")) {
                    val cleaned = if (name == "ct-int8") source else {
                        val parts = JSONObject(NativeBridge.textLight(source, "[]", false)).getJSONArray("parts")
                        (0 until parts.length()).joinToString("") { parts.getString(it) }
                    }
                    punc = OfflinePunctuation(config = OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(ctTransformer = path, numThreads = 2)))
                    val load = SystemClock.elapsedRealtime() - began
                    val text = punc!!.addPunctuation(cleaned)
                    if (name == "ct-qwen") {
                        punc!!.release(); punc = null
                        val punctMs = SystemClock.elapsedRealtime() - began
                        val model = args.getString("bench_qwen")!!
                        require(model.startsWith("/data/local/tmp/qingstudy-text-lab/"))
                        val prompt = "<|im_start|>system\n校正文本中的标点和分句。标点草稿可能断错，依据原文重新判断。保留全部字词，不改变否定、人物、数字和条件，不添加解释。只输出校正后的全文。<|im_end|>\n<|im_start|>user\n原文：$cleaned\n标点草稿：$text<|im_end|>\n<|im_start|>assistant\n"
                        val id = SystemClock.elapsedRealtimeNanos()
                        TextNative.select(id)
                        JSONObject(TextNative.generate(model.toByteArray(), prompt.toByteArray(), id).toString(Charsets.UTF_8))
                            .put("draft", text).put("punctMs", punctMs).put("cleaned", cleaned)
                            .put("endToEndMs", SystemClock.elapsedRealtime() - began)
                    } else JSONObject().put("text", text).put("cleaned", cleaned).put("complete", true).put("loadMs", load)
                        .put("totalMs", SystemClock.elapsedRealtime() - began).put("activePssKb", pss())
                } else if (name == "guided-qwen" || name == "segment-qwen") {
                    val id = SystemClock.elapsedRealtimeNanos()
                    TextNative.select(id)
                    if (name == "guided-qwen") {
                        val draft = case.getString("draft")
                        val prompt = "<|im_start|>system\n校正文本中的标点和分句，去掉句首口头语和口吃。标点草稿可能断错，依据原文重新判断。保留全部其他字词，不改变否定、人物、数字和条件，不添加解释。只输出校正后的全文。<|im_end|>\n<|im_start|>user\n原文：$source\n标点草稿：$draft<|im_end|>\n<|im_start|>assistant\n"
                        JSONObject(TextNative.generate(path.toByteArray(), prompt.toByteArray(), id).toString(Charsets.UTF_8))
                            .put("draft", draft).put("precomputedDraft", true)
                    } else {
                        val parts = case.getJSONArray("parts")
                        val results = JSONArray()
                        var complete = true
                        var load = 0L
                        for (partIndex in 0 until parts.length()) {
                            val part = parts.getString(partIndex)
                            val prompt = JSONObject(NativeBridge.textTool(part, "", false)).getString("prompt")
                            val value = JSONObject(TextNative.generate(path.toByteArray(), prompt.toByteArray(), id).toString(Charsets.UTF_8))
                            results.put(value); complete = complete && value.optBoolean("complete")
                            load += value.optLong("loadMs")
                            if (value.has("error")) break
                        }
                        JSONObject().put("text", (0 until results.length()).joinToString("\n") { results.getJSONObject(it).optString("text") })
                            .put("parts", parts).put("results", results).put("precomputedSegmentation", true)
                            .put("loadMs", load).put("complete", complete && results.length() == parts.length())
                            .put("totalMs", SystemClock.elapsedRealtime()-began).put("activePssKb", pss())
                    }
                } else if (name == "qwen25-05-v2") {
                    val id = SystemClock.elapsedRealtimeNanos()
                    TextNative.select(id)
                    TextInference.generate(path, source, id, true)
                } else {
                    var prompt = JSONObject(NativeBridge.textTool(source, "", false)).getString("prompt")
                    if (name == "qwen35-08-q4" && args.getString("bench_prompt") == "punctuation") {
                        prompt = "<|im_start|>system\n为下面的文字补全中文标点，合理断句。不要增删或改写任何字词。只输出加好标点的全文。<|im_end|>\n<|im_start|>user\n$source<|im_end|>\n<|im_start|>assistant\n"
                    }
                    // Qwen3 官方关闭思考的模板尾部；提示内容与正式版相同。
                    if (name in listOf("qwen3-06-q8", "qwen35-08-q4")) prompt += "<think>\n\n</think>\n\n"
                    val id = SystemClock.elapsedRealtimeNanos()
                    TextNative.select(id)
                    JSONObject(TextNative.generate(path.toByteArray(), prompt.toByteArray(), id).toString(Charsets.UTF_8))
                }
                row.put("answer", answer)
                if (!answer.has("error")) {
                    val review = if (name == "qwen25-05-v2") answer else JSONObject(NativeBridge.textTool(source, answer.getString("text"), answer.optBoolean("complete")))
                    row.put("guardAccepted", review.getBoolean("accepted")).put("guardReason", review.getString("reason"))
                }
            } catch (error: Exception) { row.put("error", error.toString()) }
            finally {
                punc?.release()
                if (name !in listOf("ct-int8", "ct-clean")) TextNative.release()
                running.set(false); monitor.join(1000)
                row.put("sampledPeakPssKb", peak.get()).put("samplingErrors", samplingErrors.get())
                    .put("pssAfterReleaseKb", pss())
            }
            rows.put(row)
            output.writeText(result.toString(2))
            println("BENCH $name ${case.getString("id")} ${row.optJSONObject("answer")?.optLong("totalMs")}ms")
        }
        require(rows.length() > 0)
    }
}
