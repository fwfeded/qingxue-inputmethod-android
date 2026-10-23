//! 连接参数只接受明确的局域网地址或正常校验证书的 WSS；不解析名称为私网。
package dev.qingstudy.ime

import android.content.Context
import java.net.URI
import okhttp3.Request

internal data class OnlineVoiceConfig(val url: String, val token: String) {
    fun request(): Request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    companion object {
        fun read(context: Context): OnlineVoiceConfig {
            val settings = VoiceSettings.read(context)
            return validate(settings.optString("server_url"), settings.optString("server_token"))
        }
        fun validate(rawUrl: String, rawToken: String): OnlineVoiceConfig {
            val url = rawUrl.trim(); val token = rawToken.trim()
            val uri = runCatching { URI(url) }.getOrElse { error("请输入有效的服务器地址") }
            val host = uri.host?.lowercase() ?: error("请输入 ws://电脑局域网IP:10095")
            require(uri.scheme in listOf("ws", "wss") && uri.port in -1..65535 && uri.port != 0 &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "地址仅支持 ws / wss，不含账号、参数或片段" }
            require(host !in listOf("localhost", "[::1]", "::1") && !host.startsWith("127.")) { "不能使用手机自己的回环地址，请填电脑局域网 IP" }
            val ip = host.split('.').mapNotNull { it.toIntOrNull() }
            val canonical = ip.size == 4 && ip.all { it in 0..255 } && ip.joinToString(".") == host
            val privateIp = canonical && (ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] in 16..31)
            require(uri.scheme == "wss" || privateIp) { "明文 ws 仅支持局域网 IP，其他地址请使用 wss" }
            require(token.length in 24..256 && token.all { it.code in 33..126 }) { "请粘贴电脑生成的连接密钥（24–256 个英文字符）" }
            return OnlineVoiceConfig(url, token)
        }
        fun message(code: String) = when (code) {
            "unauthorized" -> "连接密钥不正确，请检查后重试"
            "warming" -> "电脑正在加载模型，请稍后再试"
            "busy" -> "电脑正在处理另一段语音，请稍后再试"
            "unavailable" -> "电脑模型加载失败，请查看电脑服务窗口"
            else -> "连接中断或服务不可用，已保留识别文字；请检查电脑与网络"
        }
    }
}
