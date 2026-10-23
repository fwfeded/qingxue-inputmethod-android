//! 文字服务连接、超时与取消；回调带请求代次，拒收旧草稿结果。
package dev.qingstudy.ime

import android.content.*
import android.os.*
import org.json.JSONObject

internal class TextClient(context: Context, private val result: (JSONObject) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var bound = false
    private var remote: Messenger? = null
    private var pending: Message? = null
    private var current = 0L
    private val timeout = Runnable { fail("整理超时，原文已保留") }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (current != 0L && msg.data.getLong("id") == current) {
            val answer = runCatching { JSONObject(msg.data.getString("result").orEmpty()) }.getOrElse { JSONObject().put("error", "结果格式错误") }
            close(); result(answer)
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = Messenger(service); pending?.let { send(it) }; pending = null }
        override fun onServiceDisconnected(name: ComponentName) { fail("文字服务已释放，原文已保留，请重试") }
        override fun onBindingDied(name: ComponentName) { fail("文字服务中断，原文已保留") }
        override fun onNullBinding(name: ComponentName) { fail("无法连接文字服务") }
    }
    fun run(source: String) {
        close(); current = SystemClock.elapsedRealtimeNanos()
        pending = Message.obtain(null, 1).apply { replyTo = receiver; data = Bundle().apply { putLong("id", current); putString("source", source) } }
        bound = context.bindService(Intent(context, TextService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) fail("无法启动文字服务") else main.postDelayed(timeout, 125000)
    }
    private fun send(message: Message) { try { remote?.send(message) } catch (_: Exception) { fail("文字服务连接中断") } }
    private fun fail(reason: String) { if (current != 0L) { close(); result(JSONObject().put("error", reason)) } }
    override fun close() {
        val canceled = current
        current = 0; pending = null; main.removeCallbacks(timeout)
        runCatching { remote?.send(Message.obtain(null, 2).apply { replyTo = receiver; data = Bundle().apply { putLong("id", canceled) } }) }; remote = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }
}
