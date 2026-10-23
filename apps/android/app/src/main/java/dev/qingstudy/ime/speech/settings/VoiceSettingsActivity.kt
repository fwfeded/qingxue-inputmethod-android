package dev.qingstudy.ime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter

//! 沿用已批准语音分组；来源明确选择，能力差异直接说明，可选包由独立页面下载。
class VoiceSettingsActivity : Activity() {
    private lateinit var ui: SettingsUi
    private val main = Handler(Looper.getMainLooper())
    private var modelPage: VoiceModelPage? = null
    private var onlinePage: OnlineVoicePage? = null
    private var status = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show()
        if (savedInstanceState?.getBoolean("models_open") ?: intent.getBooleanExtra("open_voice_models", false)) {
            modelPage = VoiceModelPage(this, ::show).also { it.show() }
        } else if (savedInstanceState?.getBoolean("server_open") ?: intent.getBooleanExtra("open_voice_server", false)) {
            openServer()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("models_open", modelPage != null)
        outState.putBoolean("server_open", onlinePage != null)
        super.onSaveInstanceState(outState)
    }
    private fun save(key: String, value: Any) { VoiceSettings.save(this, key, value) }
    private fun choice(title: String, key: String, labels: Array<String>, values: Array<String>, default: String) {
        val selected = values.indexOf(VoiceSettings.read(this).optString(key, default)).coerceAtLeast(0)
        ui.row(title, labels[selected], compact = true) {
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected) { dialog, index ->
                save(key, values[index]); dialog.dismiss(); show()
            }.setNegativeButton("取消", null).show()
        }
    }
    private fun show() {
        modelPage?.close(); modelPage = null
        onlinePage?.close(); onlinePage = null
        ui = SettingsUi(this)
        val s = VoiceSettings.read(this)
        val system = s.optString("provider", "system") == "system"
        ui.section("识别来源")
        val provider = s.optString("provider", "system")
        val sources = android.widget.RadioGroup(this)
        val available = SystemSpeechClient.isAvailable(this)
        listOf("online" to "在线语音 · 我的电脑", "offline" to "离线语音 · 此手机", "system" to "手机语音服务").forEach { (value, label) ->
            sources.addView(android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId(); text = label + if (value == "system" && !available) "（不可用）" else ""
                textSize = 16f; minHeight = ui.dp(52); setTextColor(ui.palette.ink)
                isChecked = provider == value; isEnabled = value != "system" || available
                setOnClickListener {
                    if (value == "online") openServer() else { save("provider", value); show() }
                }
            })
        }
        ui.box.addView(sources)
        ui.label(when (provider) {
            "online" -> "连接你自己的电脑，边说边出字，松手校正。电脑关机或离开该网络后不可用；不会自动切换来源。"
            "offline" -> "录音仅在本机识别。首次需要下载语音包，完成后可断网使用。"
            else -> if (available) "由手机服务识别，可能联网；可用性与效果由该服务决定。" else "手机未提供可用的服务，请选择电脑在线语音或本机离线语音。"
        }, 12f)
        ui.row("离线语音包", if (VoiceModels.isReady(this)) "SenseVoice · 已就绪 · 无需重复下载 · 点击管理" else "未下载 · 点击下载 ${VoiceModels.totalBytes / 1024 / 1024} MiB · 支持暂停续传", compact = true) { modelPage = VoiceModelPage(this, ::show).also { it.show() } }
        ui.row("电脑语音服务", if (s.optString("server_url").isEmpty()) "设置地址、密钥并检测连接" else s.optString("server_url"), compact = true) { openServer() }
        if (system || !available) ui.label(SystemSpeechDiagnostics.summary(this), 12f).apply {
            contentDescription = "手机语音服务诊断"
        }
        ui.section("智能编辑")
        ui.row("智能文字整理", "本机模型按需下载 · 原文对照，核对后采用，支持撤回", compact = true) {
            startActivity(android.content.Intent(this, TextToolsActivity::class.java))
        }
        ui.toggle("分段后空行", "只调整识别结果中已有的分段，不自动生成段落。", s.optBoolean("blank_lines")) { save("blank_lines", it) }
        punctuation(system)
        ui.toggle("激进口水词过滤", "开启后只过滤句首独立的“嗯、呃、额”，保留句内停顿词与原意。", s.optBoolean("aggressive")) { save("aggressive", it) }
        ui.section("语音输入")
        ui.toggle("候选栏显示语音键", "关闭后仍可长按空格说话。", s.optBoolean("entry", true)) { save("entry", it) }
        ui.toggle("弱 Wi-Fi 时使用移动网络", "手机语音服务的网络策略由提供方控制；离线包管理支持单次选择 Wi-Fi 下载。", false, false) { }
        ui.toggle("使用蓝牙耳机麦克风", if (system) "手机语音服务管理音源，本开关控制电脑在线与本机离线录音。" else "需连接支持通话的耳机；连接失败会提示。", s.optBoolean("bluetooth"), !system) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            } else save("bluetooth", enabled)
        }
        ui.section("语音指令")
        ui.toggle("使用发送口令", "仅在本次最终结果末尾匹配；口令本身不会上屏。默认关闭。", s.optBoolean("send_command")) { save("send_command", it) }
        ui.row("设置发送口令", "可多选：${VoiceSettings.phrases.joinToString("、")}", compact = true) {
            val selected = s.optJSONArray("commands") ?: JSONArray(listOf(VoiceSettings.phrases[0]))
            val checked = BooleanArray(5) { i -> (0 until selected.length()).any { selected.optString(it) == VoiceSettings.phrases[i] } }
            AlertDialog.Builder(this).setTitle("发送口令").setMultiChoiceItems(VoiceSettings.phrases, checked) { _, i, value -> checked[i] = value }
                .setPositiveButton("保存") { _, _ -> save("commands", JSONArray(VoiceSettings.phrases.filterIndexed { i, _ -> checked[i] })); show() }
                .setNegativeButton("取消", null).show()
        }
        ui.label("语音修改指令（如“把周一改成周二”）需要额外语言模型，当前请选中文字后修改。英文释义和学习设置继续独立生效。", 12f)
        ui.section("性能与隐私")
        ui.label("离线识别先开始采音并后台加载模型；短时间继续说话会复用，空闲 60 秒后释放语音进程。系统识别由手机服务启动，可能因静音提前结束。本机最长录音 60 秒，电脑在线最长 30 秒；两端均不保存音频文件。电脑模型常驻复用，手机不加载在线模型。", 12f)
        ui.button("立即释放语音内存") { VoiceClient(this, EmptyVoiceListener()).apply { release(); main.postDelayed({ close() }, 1500) }; status = "已请求释放语音进程"; show() }
        if (status.isNotEmpty()) ui.label(status)
        ui.show("语音输入", ::finish)
    }
    private fun openServer() { onlinePage = OnlineVoicePage(this, ::show).also { it.show() } }
    private fun punctuation(system: Boolean) {
        val labels = arrayOf("保留所有标点", "句末不加标点", "智能添加标点（模型）", "空格替代标点")
        val values = arrayOf("all", "end", "model", "space")
        val supported = system && Build.VERSION.SDK_INT >= 33
        val selected = values.indexOf(VoiceSettings.read(this).optString("punctuation", "all")).coerceAtLeast(0)
        val summary = labels[selected] + if (selected == 2 && !supported) " · 当前来源不支持，按原文保留" else ""
        ui.row("标点符号", summary, compact = true) {
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
                override fun isEnabled(position: Int) = position != 2 || supported
                override fun getView(position: Int, recycled: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                    super.getView(position, recycled, parent).apply { isEnabled = isEnabled(position); alpha = if (isEnabled) 1f else 0.4f }
            }
            AlertDialog.Builder(this).setTitle("标点符号")
                .setSingleChoiceItems(adapter, selected) { dialog, index ->
                    if (index != 2 || supported) { save("punctuation", values[index]); dialog.dismiss(); show() }
                }.setNegativeButton("取消", null).show()
        }
        ui.label(if (supported) "智能标点会请求手机服务格式化；服务可能忽略该请求，效果取决于提供方。"
            else "智能添加标点需 Android 13 以上的手机语音服务支持；当前来源保留识别原文。", 12f)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 2) { save("bluetooth", results.firstOrNull() == PackageManager.PERMISSION_GRANTED); show() }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (modelPage != null || onlinePage != null) show() else super.onBackPressed() }
    override fun onStop() { modelPage?.pause(); super.onStop() }
    override fun onDestroy() { onlinePage?.close(); modelPage?.close(); super.onDestroy() }
}

open class EmptyVoiceListener : VoiceClient.Listener {
    override fun onState(state: String, message: String) = Unit
    override fun onPartial(text: String) = Unit
    override fun onResult(text: String, decodeMs: Long) = Unit
    override fun onMetrics(loadMs: Long, reused: Boolean) = Unit
}
