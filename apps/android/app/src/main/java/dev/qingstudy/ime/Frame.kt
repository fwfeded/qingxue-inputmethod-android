// 原版引擎的展示帧，安卓只消费数据，不重新排序。
package dev.qingstudy.ime
import org.json.JSONObject

data class Frame(val input: String, val candidates: List<Pair<String, String>>, val commit: String, val delete: Boolean,
    val marked: String = input, val english: Boolean = false, val expression: Boolean = false,
    val scheme: String = "全拼", val panel: String = "", val senses: List<List<String>> = emptyList(),
    val fresh: List<Boolean> = emptyList(), val displays: List<String> = emptyList(), val custom: List<Boolean> = emptyList(),
    val phoneticKeys: Map<String, String> = emptyMap(), val preedit: String = input,
    val panelGroup: String = "", val panelSections: List<String> = emptyList(), val panelCategory: Int = 0, val panelColumns: Int = 6,
    val continuation: Boolean = false, val blockable: List<Boolean> = emptyList(),
    val blockAllowed: Boolean = true, val blockedWords: List<String>? = null, val learnedVoiceWords: Int = 0) {
    companion object {
        fun parse(source: String): Frame {
            val json = JSONObject(source)
            val items = json.getJSONArray("candidates")
            return Frame(json.getString("input"), List(items.length()) { index ->
                val item = items.getJSONObject(index)
                item.getString("text") to item.getString("gloss")
            }, json.getString("commit"), json.getBoolean("delete"), json.optString("marked", json.getString("input")),
                json.optBoolean("english"), json.optBoolean("expression"), json.optString("scheme", "全拼"),
                if (json.isNull("panel")) "" else json.optString("panel"),
                List(items.length()) { i -> val array = items.getJSONObject(i).optJSONArray("senses")
                    if (array == null) emptyList() else List(array.length()) { array.getString(it) } },
                List(items.length()) { items.getJSONObject(it).optBoolean("fresh") },
                List(items.length()) { items.getJSONObject(it).optString("display", items.getJSONObject(it).getString("text")) },
                List(items.length()) { items.getJSONObject(it).optBoolean("custom") },
                json.optJSONObject("phoneticKeys")?.let { keys -> keys.keys().asSequence().associateWith { keys.getString(it) } } ?: emptyMap(),
                json.optString("preedit", json.getString("input")), json.optString("panelGroup"),
                json.optJSONArray("panelSections")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList(),
                json.optInt("panelCategory"), json.optInt("panelColumns", 6),
                json.optBoolean("continuation"),
                List(items.length()) { items.getJSONObject(it).optBoolean("blockable") },
                json.optBoolean("blockAllowed", true),
                json.optJSONArray("blockedWords")?.let { array -> List(array.length()) { array.getString(it) } },
                json.optInt("learnedVoiceWords"))
        }
    }
}
