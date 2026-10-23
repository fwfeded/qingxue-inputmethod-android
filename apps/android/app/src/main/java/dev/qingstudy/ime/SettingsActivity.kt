// 离线功能设置与上游学习模块汇总，所有选项在下一次开始输入时加载。
package dev.qingstudy.ime
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import org.json.JSONObject
import java.io.File

class SettingsActivity : Activity() {
    private lateinit var navigation: SettingsNavigator
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = SettingsNavigator(this, savedInstanceState, ::showLearning)
    }
    override fun onResume() { super.onResume(); navigation.resume() }
    override fun onPause() { navigation.pause(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); navigation.save(out) }
    @Deprecated("Android legacy back dispatch")
    override fun onBackPressed() { navigation.back() }
    private fun showLearning() {
        val directory = runCatching { LearningBackupFiles.directory(this) }.getOrElse {
            AlertDialog.Builder(this).setMessage("无法读取学习记录：${it.message}").setPositiveButton("关闭", null).show()
            return
        }
        val summary = runCatching { JSONObject(File(directory, "summary.json").readText()) }.getOrElse { JSONObject() }
        val words = runCatching { File(directory, "vocabulary.tsv").readLines().filter { !it.startsWith("#") && it.isNotBlank() }
            .takeLast(200).mapNotNull { line -> val fields = line.split('\t'); if (fields.size < 7) null else "${fields[1]} · 见过 ${fields[2]} 次 · 使用 ${fields[4]} 次" }.joinToString("\n") }.getOrDefault("")
        val vocabularySummary = if (summary.optString("learningLanguage", "en") == "off")
            "英文释义已关闭；已有词汇记录仍保留。重新开启并输入后更新英语学习汇总。"
        else "见过译词 ${summary.optLong("seen")} · 熟悉 ${summary.optLong("familiar")} · 用过 ${summary.optLong("used")}"
        AlertDialog.Builder(this).setTitle("本机学习记录")
            .setMessage("今日汉字 ${summary.optLong("today")} · 累计 ${summary.optLong("total")}\n英文词 ${summary.optLong("english")} · 使用天数 ${summary.optLong("days")}\n$vocabularySummary\n\n词汇记录（最多显示 200 条）：\n${words.ifEmpty { "暂无记录；可开启本机学习与英文释义后输入并选词。" }}")
            .setNeutralButton("英语分级统计") { _, _ -> VocabularyLevels.show(this, summary) }
            .setPositiveButton("关闭", null).show()
    }
}
