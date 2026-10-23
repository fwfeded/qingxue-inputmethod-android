package dev.qingstudy.ime

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

// AtomicFile 的读取也会清理 .new，必须与保存共用锁；只原子写不能保护跨进程读写。
object VoiceSettings {
    val phrases = arrayOf("帮我发送", "我说完了", "立即发送", "发送发送", "overover")
    private fun readLocked(context: Context): JSONObject {
        val source = File(context.filesDir, "voice-settings.json")
        val legacy = source.exists() || File(context.filesDir, "voice-settings.json.bak").exists() ||
            File(context.filesDir, "voice/models/verified").isFile
        val settings = runCatching { JSONObject(AtomicFile(source).readFully().toString(Charsets.UTF_8)) }
            .getOrElse { JSONObject() }
        // 旧版只有离线语音，升级不得把已有选择悄悄改成可能联网的系统来源。
        if (settings.optString("provider") !in listOf("system", "offline", "online")) {
            settings.put("provider", if (legacy) "offline" else "system")
            // 保存失败也不能丢弃已判定的离线来源；保留内存结果，下次读取重试。
            runCatching { writeLocked(context, settings) }.onFailure {
                android.util.Log.w("QingVoice", "语音来源迁移暂未写入，保留当前来源", it)
            }
        }
        return settings
    }
    @Synchronized private fun <T> locked(context: Context, action: () -> T): T =
        RandomAccessFile(File(context.filesDir, "voice-settings.lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }
    // 首次解锁前 CE 存储不可用时，仍允许 Direct Boot 键盘使用默认设置。
    fun read(context: Context): JSONObject = runCatching { locked(context) { readLocked(context) } }
        .getOrElse { JSONObject().put("provider", "offline") }
    fun save(context: Context, key: String, value: Any) = locked(context) {
        writeLocked(context, readLocked(context).put(key, value))
    }
    fun saveServer(context: Context, url: String, token: String) = locked(context) {
        OnlineVoiceConfig.validate(url, token)
        writeLocked(context, readLocked(context).put("server_url", url.trim())
            .put("server_token", token.trim()).put("provider", "online"))
    }
    private fun writeLocked(context: Context, settings: JSONObject) {
        val file = AtomicFile(File(context.filesDir, "voice-settings.json"))
        val data = settings.toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    fun format(context: Context, text: String, finalResult: Boolean): Pair<String, Boolean> {
        val settings = read(context)
        val options = JSONObject().put("punctuation", settings.optString("punctuation", "all"))
            .put("blank_lines", settings.optBoolean("blank_lines"))
            .put("aggressive", settings.optBoolean("aggressive"))
            .put("commands", if (settings.optBoolean("send_command")) settings.optJSONArray("commands") ?: JSONArray(listOf(phrases[0])) else JSONArray())
        val result = JSONObject(NativeBridge.voiceText(text, options.toString(), finalResult))
        return result.getString("text") to result.getBoolean("send")
    }
}
