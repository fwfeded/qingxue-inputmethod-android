//! 无录音的连接检测；超时、关闭和返回只触发一次，页面离开后可取消。
package dev.qingstudy.ime

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class OnlineVoiceProbe(private val result: (Boolean, String) -> Unit) : WebSocketListener() {
    private val main = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private val timeout = Runnable { finish(false, "连接超时，请确认电脑服务已启动、手机与电脑在同一网络") }
    fun start(config: OnlineVoiceConfig) { main.postDelayed(timeout, 6000); socket = http.newWebSocket(config.request(), this) }
    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"op\":\"status\"}") }
    override fun onMessage(webSocket: WebSocket, text: String) {
        val data = runCatching { JSONObject(text) }.getOrElse { finish(false, "服务器协议不兼容"); return }
        val ready = data.optString("event") == "status" && data.optString("state") == "ready" &&
            data.optInt("protocol") == 1 && data.optString("final_engine") == "sensevoice"
        finish(ready, if (ready) "连接成功 · FunASR 流式出字 + SenseVoice 松手校正" else
            OnlineVoiceConfig.message(data.optString("error", data.optString("state"))))
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish(false, "无法连接，请检查地址、网络和电脑服务") }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(false, "服务器已断开，请重新检测") }
    private fun finish(ok: Boolean, message: String) {
        if (done.compareAndSet(false, true)) { main.removeCallbacks(timeout); socket?.cancel(); main.post { result(ok, message) } }
    }
    fun close() { done.set(true); main.removeCallbacks(timeout); socket?.cancel() }
    companion object {
        // 不跟随重定向，不绕过系统证书验证；共享连接设施，不共享识别会话。
        val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
