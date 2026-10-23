// 只展示 Rust 快照中的分级结果；逐级原生文本控件适配大字体和无障碍阅读。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

object VocabularyLevels {
    fun show(activity: Activity, summary: JSONObject) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(value: String) {
            content.addView(TextView(activity).apply {
                // 原生 TextView 直接暴露全文，避免简短 contentDescription 覆盖数字和说明。
                text = value; textSize = 16f
                setPadding(0, 0, 0, padding)
            })
        }
        val levels = summary.optJSONArray("levels")
        when {
            summary.optString("learningLanguage", "en") == "off" -> label("英语分级统计已暂停，原记录保留。重新开启英文释义并输入后更新。")
            levels == null -> label("英语分级统计将在下次输入后更新。")
            levels.length() == 0 -> label("英语等级表暂不可用；基础输入和词汇记录不受影响。")
            else -> {
                label("按不同译词计数；看熟表示见过至少 3 轮，选词表示选过带该译词的中文候选。")
                for (index in 0 until levels.length()) {
                    val level = levels.getJSONObject(index)
                    val name = level.getString("name")
                    label("$name：见过 ${level.getLong("seen")} / 词表 ${level.getLong("total")}\n看熟 ${level.getLong("familiar")} · 选词 ${level.getLong("committed")}")
                }
                label("未收录的译词或短语只计入总数。此分布不代表英语能力评级。")
            }
        }
        label("来源：CEFR-J / Octanove。完整署名见主页「开源与数据许可」。")
        AlertDialog.Builder(activity).setTitle("英语词汇分级（A1–C2）")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("关闭", null).show()
    }
}
