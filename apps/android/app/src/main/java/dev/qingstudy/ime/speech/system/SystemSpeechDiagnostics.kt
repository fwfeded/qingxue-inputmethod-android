//! 仅保存系统语音的错误码和默认提供方，不记录音频、识别文字或服务器密钥。
package dev.qingstudy.ime

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

internal object SystemSpeechDiagnostics {
    fun provider(context: Context): String = runCatching {
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToShortString()
    }.getOrNull() ?: "系统未公布默认提供方"

    fun record(context: Context, code: Int) {
        runCatching {
            context.getSharedPreferences("speech-diagnostics", Context.MODE_PRIVATE).edit()
                .putInt("error", code).putString("provider", provider(context)).apply()
        }
    }

    fun summary(context: Context): String {
        val name = provider(context)
        val prefs = context.getSharedPreferences("speech-diagnostics", Context.MODE_PRIVATE)
        val last = if (prefs.contains("error") && prefs.getString("provider", "") == name)
            "\n最近一次失败：Android 错误码 ${prefs.getInt("error", 0)}。" else ""
        return "默认提供方：$name。检测到服务不代表它支持中文或能够联网识别。$last"
    }
}
