// 设置分类、搜索与页面状态；现有数据页面仍使用原来的实际业务入口。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class SettingsNavigator(private val activity: Activity, saved: Bundle?, private val learningSummary: () -> Unit) {
    private var page = saved?.getString("page") ?: activity.intent.getStringExtra("page") ?: "home"
    private var query = saved?.getString("query") ?: ""
    private var scroll = saved?.getInt("scroll") ?: 0
    private lateinit var ui: SettingsUi
    private val titles = mapOf("home" to "设置", "input" to "输入与候选", "dictionary" to "词库与短语", "learning" to "学习与隐私", "blocked" to "不再推荐的词", "fuzzy" to "模糊音", "about" to "关于青学")
    fun resume() { show(); ui.scroll.post { ui.scroll.scrollTo(0, scroll) } }
    fun pause() { scroll = ui.scroll.scrollY }
    fun save(out: Bundle) { out.putString("page", page); out.putString("query", query); out.putInt("scroll", ui.scroll.scrollY) }
    fun back() { if (page == "home") activity.finish() else navigate(when(page) { "fuzzy" -> "input"; "blocked" -> "learning"; else -> "home" }) }
    private fun store(key: String, value: Any) { Preferences.save(activity, Preferences.read(activity).put(key, value)) }
    private fun navigate(next: String) {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.currentFocus?.windowToken, 0)
        query = ""; scroll = 0
        val target = when (next) {
            "text" -> TextToolsActivity::class.java
            "textModels" -> TextModelActivity::class.java
            "voice", "voiceModels" -> VoiceSettingsActivity::class.java
            "appearance", "feedback", "appearanceSize" -> AppearanceActivity::class.java
            "domains" -> DomainActivity::class.java
            "transfer" -> DictionaryActivity::class.java
            "phrases" -> PhraseActivity::class.java
            "backup" -> LearningBackupActivity::class.java
            else -> null
        }
        if (target != null) activity.startActivity(Intent(activity, target).apply {
            if (next == "voiceModels") putExtra("open_voice_models", true)
            if (next == "feedback") putExtra("appearance_tab", "feedback")
            if (next == "appearanceSize") putExtra("appearance_tab", "size")
        }) else { page = next; show() }
    }
    private fun choice(title: String, key: String, names: Array<String>, values: Array<String>) {
        ui.label(title, 16f, false)
        ui.box.addView(Spinner(activity).apply {
            contentDescription = title
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, names) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = super.getView(position, convertView, parent).apply { (this as TextView).setTextColor(ui.palette.ink) }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = super.getDropDownView(position, convertView, parent).apply { (this as TextView).setTextColor(ui.palette.ink); setBackgroundColor(ui.palette.background) }
            }
            setSelection(values.indexOf(Preferences.read(activity).optString(key, values[0])).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { store(key, values[position]) }
            }
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
    }
    private fun toggle(title: String, key: String, default: Boolean) { ui.toggle(title, "", Preferences.read(activity).optBoolean(key, default)) { store(key, it) } }
    private fun show() {
        ui = SettingsUi(activity)
        when (page) {
            "home" -> home()
            "input" -> input()
            "dictionary" -> {
                ui.label("管理输入所需的词，不与学习数据混放。")
                ui.row("内置领域词库", "11 类领域，按需启用", "▤") { navigate("domains") }
                ui.row("词库导入与导出", "多选文件、查看结果与导出", "⇄") { navigate("transfer") }
                ui.row("自定义短语", "编码、内容、位置与文件导入导出", "文") { navigate("phrases") }
            }
            "learning" -> learning()
            "blocked" -> blocked()
            "fuzzy" -> {
                ui.label("只开启需要的发音组合。")
                for ((key, title) in listOf("z_zh" to "z / zh", "c_ch" to "c / ch", "s_sh" to "s / sh", "n_l" to "n / l", "f_h" to "f / h", "l_r" to "l / r", "an_ang" to "an / ang", "en_eng" to "en / eng", "in_ing" to "in / ing")) toggle(title, key, false)
            }
            "about" -> about()
            else -> { page = "home"; home() }
        }
        if (page != "home") ui.button("返回输入") { activity.finish() }
        ui.show(titles[page] ?: "设置", ::back)
    }
    private fun home() {
        ui.label("让输入方式，适合你的习惯。")
        val search = EditText(activity).apply {
            hint = "搜索：字体、词库、模糊音…"; contentDescription = "搜索设置"; textSize = 15f
            isSingleLine = true; setTextColor(ui.palette.ink); setHintTextColor(ui.palette.muted)
            background = ui.palette.surface(ui.palette.key); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); setText(query)
        }
        ui.box.addView(search, LinearLayout.LayoutParams(-1, -2))
        ui.box.isFocusableInTouchMode = true; ui.box.requestFocus()
        val start = ui.box.childCount
        val categories = listOf(
            arrayOf("text", "智能文字整理", "离线文字工具 · 核对后采用，支持撤回", "文"),
            arrayOf("textModels", "离线文字包", "Qwen 模型按需下载、续传与删除", "↓"),
            arrayOf("voice", "语音输入", "识别来源、标点、发送口令与模型管理", "♩"),
            arrayOf("voiceModels", "离线语音包", if (VoiceModels.isReady(activity)) "已就绪 · 无需重复下载 · 可管理或删除" else "下载 ${VoiceModels.totalBytes / 1024 / 1024} MiB · 支持暂停续传", "↓"),
            arrayOf("appearance", "键盘与外观", "主题、按键反馈、高度、字体、间距、圆角、底部留白、副符号、浅深色", "⌨"),
            arrayOf("input", "输入与候选", "全拼 / 双拼 / 注音、简繁与模糊音", "文"),
            arrayOf("dictionary", "词库与短语", "领域词库、导入导出、自定义短语", "▤"),
            arrayOf("learning", "学习与隐私", habitState() + " · 英文释义与备份", "Aa"),
            arrayOf("about", "关于青学", "版本、帮助与开源许可", "ⓘ"))
        val details = listOf(
            arrayOf("feedback", "按键反馈", "震动／振动强度、跟随系统、按键音效与音量", "♩"),
            arrayOf("appearanceSize", "尺寸与文字", "行高、字号与固定预览", "⌨"),
            arrayOf("voiceModels", "离线语音包", "下载、续传、删除与模型管理", "♩"), arrayOf("learning", "学习输入习惯", "本机学习开关、常用词与选词偏好", "Aa"), arrayOf("fuzzy", "模糊音", "发音组合", "文"), arrayOf("transfer", "词库导入与导出", "文件管理", "⇄"), arrayOf("backup", "学习记录备份", "导出与恢复", "▤"), arrayOf("phrases", "自定义短语", "编码和内容", "文"), arrayOf("domains", "内置领域词库", "11 类词库", "▤"))
        fun results(value: String) {
            ui.box.removeViews(start, ui.box.childCount - start)
            val rows = if (value.isBlank()) categories else (categories + details)
                .filter { (it[1] + it[2]).contains(value.trim(), true) }.distinctBy { it[0] }
            rows.forEach { item -> ui.row(item[1], item[2], item[3]) { navigate(item[0]) } }
            if (rows.isEmpty()) ui.label("没有匹配项，试试“字体”或“词库”。")
            ui.button("返回输入") { activity.finish() }
        }
        results(query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); results(query) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun input() {
        ui.label("把方案、候选规则和标点集中在这里。")
        choice("输入方案", "scheme", arrayOf("全拼", "大千注音", "小鹤双拼", "自然码", "微软双拼", "搜狗双拼", "智能ABC", "小浪双拼", "首道双拼", "拼音加加"), arrayOf("pinyin", "zhuyin", "xiaohe", "ziranma", "microsoft", "sogou", "abc", "xiaolang", "shoudao", "jiajia"))
        choice("简繁输出", "traditional", arrayOf("简体", "繁体（台湾）", "繁体（香港）", "繁体（通用）"), arrayOf("off", "taiwan", "hongkong", "standard"))
        ui.row("模糊音", "9 组发音，按需开启") { navigate("fuzzy") }
        toggle("中文输入时也给英文候选", "mixed", true)
        toggle("中文候选优先", "chineseFirst", false)
        toggle("中西文之间自动空格", "autoSpace", false)
        toggle("中文全角标点", "punctuation", true)
        toggle("候选中的表情和符号", "emoji", true)
        ui.label("更改自动保存，下次进入输入框生效。", 12f)
    }
    private fun learning() {
        val prefs = Preferences.read(activity)
        ui.label("释义显示与学习记录分开控制。")
        ui.toggle("中文候选英文释义", "关闭后隐藏释义与生词标记，不删除已有词汇。", prefs.optString("learningLanguage", "en") != "off") { store("learningLanguage", if (it) "en" else "off") }
        val master = ui.toggle("本机学习、词汇记录与统计", "默认关闭；数据仅保存在本机。", prefs.optBoolean("learning", false)) { store("learning", it) }
        val habit = ui.toggle("学习输入习惯", "仅在总开关开启时生效；关闭后已学排序保留，词汇记录与统计继续。", prefs.optBoolean("habitLearning", true), master.isChecked) { store("habitLearning", it) }
        val next = ui.toggle("上屏后推荐续词", "根据本机已确认的词序，点击续词上屏；输入拼音后切回普通候选。", prefs.optBoolean("nextWordSuggestions", true), master.isChecked && habit.isChecked) { store("nextWordSuggestions", it) }
        val voice = ui.toggle("学习语音手动纠正", "仅在修改后点完成或发送时记入词语偏好；不学习未修改的听写或自动整理。", prefs.optBoolean("voiceCorrectionLearning", true), master.isChecked && habit.isChecked) { store("voiceCorrectionLearning", it) }
        val state = ui.label(habitState(), 14f, false).apply { contentDescription = "输入习惯学习状态" }
        fun update() { habit.isEnabled = master.isChecked; next.isEnabled = master.isChecked && habit.isChecked; voice.isEnabled = next.isEnabled; state.text = habitState() }
        habit.setOnCheckedChangeListener { _, enabled -> store("habitLearning", enabled); update() }
        master.setOnCheckedChangeListener { _, enabled ->
            store("learning", enabled); update()
        }
        ui.label("重复确认可积累最长 12 字的个人短语。语音纠正仅学习词库中能确定读音的已知词，不保存完整听写，不改变语音模型本身。", 12f)
        ui.row("不再推荐的词", "${prefs.optJSONArray("blockedWords")?.length() ?: 0} 项 · 与学习开关独立，可逐项恢复") { navigate("blocked") }
        ui.row("清除当前输入习惯", "重置词频、个人词、词序和纠错；保留英文词汇与统计") {
            AlertDialog.Builder(activity).setTitle("清除当前输入习惯？")
                .setMessage("将清除当前习惯记录。英文词汇、统计、手工短语与屏蔽列表保留；以前导出的备份不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    Thread {
                        val result = runCatching {
                            val json = org.json.JSONObject(NativeBridge.learning(java.io.File(activity.filesDir, "learning").absolutePath, "clear-habits", ""))
                            if (json.has("error")) error(json.getString("error"))
                        }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                AlertDialog.Builder(activity).setMessage(result.fold({ "当前输入习惯已清除，返回输入后生效。" }, { "清除失败：${it.message}" })).setPositiveButton("知道了", null).show()
                            }
                        }
                    }.start()
                }.show()
        }
        ui.row("查看学习记录与统计", "见过、熟悉、用过的词汇与英语分级", click = learningSummary)
        ui.row("学习记录备份", "导出或预览后恢复记录") { navigate("backup") }
        ui.label("密码及私密输入不记录。学习数据保存在本机，不上传；未开启完整输入日志。语音包下载和手机语音服务可能联网。外观重置不影响学习数据。", 12f)
    }
    private fun blocked() {
        ui.label("长按候选选择“不再推荐”。精确屏蔽该候选，不影响包含它的其他整句；最多 256 项。")
        val words = Preferences.read(activity).optJSONArray("blockedWords")
        if (words == null || words.length() == 0) { ui.label("暂无屏蔽的候选"); return }
        for (i in 0 until words.length()) {
            val word = words.optString(i)
            ui.row(word, "点击恢复推荐", "↶") {
                val latest = Preferences.read(activity)
                val list = latest.optJSONArray("blockedWords")
                val keep = org.json.JSONArray()
                if (list != null) for (j in 0 until list.length()) if (list.optString(j) != word) keep.put(list.optString(j))
                Preferences.save(activity, latest.put("blockedWords", keep)); show()
            }
        }
    }
    private fun habitState(): String {
        val prefs = Preferences.read(activity)
        return when {
            !prefs.optBoolean("learning", false) -> "习惯学习未开启：请先开启本机学习总开关"
            !prefs.optBoolean("habitLearning", true) -> "习惯学习已暂停：保留已有排序，不新增习惯"
            else -> "正在学习选词习惯：仅保存在本机"
        }
    }
    private fun about() {
        ui.label("青学输入法", 22f, false)
        ui.label("版本 ${activity.packageManager.getPackageInfo(activity.packageName, 0).versionName} · 基于青简引擎的独立安卓项目")
        ui.label("支持全拼、双拼、注音与英文。长按候选或点 Aa 查看完整释义；空格选词，回车原样提交。")
        ui.label("快捷输入：rq 日期，sj 时间，xq 星期；v1+2 算式；v123 中文数字；u4e00 字符。双拼使用大写 V/U 前缀。")
        ui.row("开源与数据许可", "青简及词库数据的来源和许可") {
            val license = activity.assets.open("licenses/LICENSE").bufferedReader().use { it.readText() }
            val notice = activity.assets.open("licenses/levels/NOTICE-en.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(activity).setTitle("开源与数据许可").setMessage("青简：https://github.com/qingjian-team/qingjian\n数据许可随包保存在 assets/licenses。\n\n$notice\n\n$license").setPositiveButton("关闭", null).show()
        }
        ui.label("界面参考 HeliBoard 的布局、Fcitx5 Android 的候选交互、FlorisBoard 的设置分类；没有复制这些项目的源码或素材。", 12f)
    }
}
