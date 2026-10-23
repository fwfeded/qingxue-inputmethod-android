//! 已确认预览的连接页：来源选择独立于参数编辑，检测通过同一份参数才能保存启用。
package dev.qingstudy.ime

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView

internal class OnlineVoicePage(private val activity: Activity, private val back: () -> Unit) {
    private var probe: OnlineVoiceProbe? = null
    private var generation = 0
    private lateinit var address: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var test: Button
    private var verified: OnlineVoiceConfig? = null
    fun show() {
        val ui = SettingsUi(activity)
        val settings = VoiceSettings.read(activity)
        ui.section("连接我的电脑")
        ui.label("手机和电脑接入同一局域网，电脑保持开机并运行青学语音服务。", 14f)
        ui.label("服务器地址", 14f, false)
        address = EditText(activity).apply {
            contentDescription = "语音服务器地址"; hint = "ws://192.168.1.8:10095"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(); textSize = 16f; setTextColor(ui.palette.ink)
            setText(settings.optString("server_url")); ui.box.addView(this, LinearLayout.LayoutParams(-1, ui.dp(52)))
        }
        ui.label("连接密钥", 14f, false)
        token = EditText(activity).apply {
            contentDescription = "语音连接密钥"; hint = "粘贴电脑启动窗口中的密钥"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f; setTextColor(ui.palette.ink)
            setText(settings.optString("server_token")); ui.box.addView(this, LinearLayout.LayoutParams(-1, ui.dp(52)))
        }
        ui.toggle("显示密钥", "", false) { visible ->
            token.inputType = InputType.TYPE_CLASS_TEXT or if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            token.setSelection(token.length())
        }
        ui.label("仅向你设置的电脑发送本次录音，电脑不保存录音。局域网 ws 不加密，请使用可信网络；其他地址必须为 wss。", 12f)
        status = ui.label("尚未检测连接", 14f, false).apply { contentDescription = "服务器连接状态" }
        test = ui.button("检测连接") { checkConnection() }
        save = ui.button("保存并使用在线语音", true) {
            val current = runCatching { config() }.getOrNull()
            if (current != null && current == verified) {
                runCatching { VoiceSettings.saveServer(activity, current.url, current.token) }
                    .onSuccess { close(); back() }.onFailure { status.text = "保存失败，请重试" }
            }
        }.apply { isEnabled = false }
        ui.label("检测只检查服务状态，不使用麦克风。识别时由 FunASR 实时出字，松手后由 SenseVoice 校正本次文字。", 12f)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { invalidate(); status.text = "参数已修改，请重新检测" }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        address.addTextChangedListener(watcher); token.addTextChangedListener(watcher)
        ui.show("电脑语音服务") { close(); back() }
    }
    private fun config() = OnlineVoiceConfig.validate(address.text.toString(), token.text.toString())
    private fun invalidate() { generation++; probe?.close(); probe = null; verified = null; save.isEnabled = false; test.isEnabled = true }
    private fun checkConnection() {
        invalidate()
        val current = runCatching { config() }.getOrElse { status.text = it.message; return }
        val attempt = generation
        status.text = "正在检测…"; test.isEnabled = false
        probe = OnlineVoiceProbe { ok, message ->
            if (generation == attempt) {
                status.text = message; test.isEnabled = true
                verified = if (ok) current else null; save.isEnabled = ok
            }
        }.also { it.start(current) }
    }
    fun close() { generation++; probe?.close(); probe = null }
}
