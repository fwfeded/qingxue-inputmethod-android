//! JNI 推理编排；清理、分段和验收规则都由 Rust 提供，每次请求共用已加载权重。
package dev.qingstudy.ime

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal object TextInference {
    fun generate(path: String, source: String, id: Long, light: Boolean): JSONObject {
        if (!light) {
            val prompt = JSONObject(NativeBridge.textTool(source, "", false)).getString("prompt")
            val answer = infer(path, prompt, id)
            if (answer.has("error")) return answer
            val review = JSONObject(NativeBridge.textTool(source, answer.getString("text"), answer.optBoolean("complete")))
            return answer.put("accepted", review.getBoolean("accepted")).put("reason", review.getString("reason"))
        }
        val started = SystemClock.elapsedRealtime()
        val plan = JSONObject(NativeBridge.textLight(source, "[]", false))
        val prompts = plan.getJSONArray("prompts")
        val outputs = JSONArray(); val chunks = JSONArray()
        var complete = true; var load = 0L; var first = 0L; var pss = 0L
        for (index in 0 until prompts.length()) {
            check(SystemClock.elapsedRealtime() - started < 120_000) { "整理超时，已保留原文，请分段处理" }
            val answer = JSONObject(TextNative.generateConstrained(path.toByteArray(Charsets.UTF_8), prompts.getString(index).toByteArray(Charsets.UTF_8),
                plan.getJSONArray("grammars").getString(index).toByteArray(Charsets.UTF_8), id).toString(Charsets.UTF_8))
            if (answer.has("error")) return answer
            if (index == 0) first = answer.optLong("firstMs")
            load += answer.optLong("loadMs"); pss = maxOf(pss, answer.optLong("activePssKb"))
            complete = complete && answer.optBoolean("complete")
            outputs.put(answer.getString("text")); chunks.put(answer)
            if (!complete) break
        }
        val review = JSONObject(NativeBridge.textLight(source, outputs.toString(), complete))
        return JSONObject().put("text", if (review.optBoolean("accepted")) review.getString("text") else (0 until outputs.length()).joinToString("\n") { outputs.getString(it) })
            .put("accepted", review.getBoolean("accepted")).put("reason", review.getString("reason"))
            .put("complete", complete).put("loadMs", load).put("firstMs", first)
            .put("totalMs", SystemClock.elapsedRealtime() - started).put("activePssKb", pss)
            .put("chunks", chunks).put("parts", plan.getJSONArray("parts"))
    }
    private fun infer(path: String, prompt: String, id: Long) =
        JSONObject(TextNative.generate(path.toByteArray(Charsets.UTF_8), prompt.toByteArray(Charsets.UTF_8), id).toString(Charsets.UTF_8))
}
