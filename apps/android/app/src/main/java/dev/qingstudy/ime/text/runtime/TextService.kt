//! 独立文字进程，串行推理并在每次完成后释放模型；IPC 仅接受本应用 UID。
package dev.qingstudy.ime

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.JSONObject

class TextService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("text-model", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var worker: Handler
    @Volatile private var active = 0L
    private var newestRequest = 0L
    private var reply: Messenger? = null
    private var death: IBinder.DeathRecipient? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.sendingUid == Process.myUid()) when (msg.what) {
            1 -> start(msg)
            2 -> if (msg.data.getLong("id") == active && msg.replyTo?.binder == reply?.binder) cancel()
        }
        true
    })
    override fun onCreate() { super.onCreate(); thread.start(); worker = Handler(thread.looper) }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() {
        cancel(); worker.post { TextNative.release(); thread.quitSafely() }
        super.onDestroy()
        // 仅结束本服务独占的 :text 进程，不影响 :ime 或设置页。
        Process.killProcess(Process.myPid())
    }
    private fun cancel() {
        active = 0; TextNative.select(0)
        death?.let { runCatching { reply?.binder?.unlinkToDeath(it, 0) } }; death = null; reply = null
    }
    private fun start(msg: Message) {
        val id = msg.data.getLong("id"); if (id == 0L) return
        val target = msg.replyTo ?: return
        // 两个窗口的绑定回调可能乱序；用户后发起的请求始终优先。
        if (id <= newestRequest) {
            runCatching { target.send(Message.obtain(null, 3).apply { data = Bundle().apply {
                putLong("id", id); putString("result", JSONObject().put("error", "此请求已过期，原文保留").toString())
            } }) }
            return
        }
        newestRequest = id
        val previousId = active
        runCatching { reply?.send(Message.obtain(null, 3).apply {
            data = Bundle().apply { putLong("id", previousId); putString("result", JSONObject().put("error", "已有新的整理请求，原文保留").toString()) }
        }) }
        cancel()
        val source = msg.data.getString("source").orEmpty()
        active = id; reply = target; TextNative.select(id)
        death = IBinder.DeathRecipient { main.post { if (active == id) cancel() } }
        try { target.binder.linkToDeath(death!!, 0) } catch (_: Exception) { cancel(); return }
        worker.post {
            val result = runCatching {
                check(source.length <= 1200) { "原文过长" }
                val prompt = JSONObject(NativeBridge.textTool(source, "", false)).getString("prompt")
                TextModels.locked(this, { active != id }) {
                    check(TextModels.ready(this)) { "请先下载离线文字包" }
                    try {
                        val answer = JSONObject(TextNative.generate(TextModels.model(this).absolutePath.toByteArray(Charsets.UTF_8), prompt.toByteArray(Charsets.UTF_8), id).toString(Charsets.UTF_8))
                        if (answer.has("error")) error(answer.getString("error"))
                        val review = JSONObject(NativeBridge.textTool(source, answer.getString("text"), answer.optBoolean("complete")))
                        answer.put("accepted", review.getBoolean("accepted")).put("reason", review.getString("reason"))
                    } finally { TextNative.release() }
                }
            }.getOrElse { JSONObject().put("error", it.message ?: "推理失败，已保留原文") }
            main.post {
                if (active == id) {
                    runCatching { target.send(Message.obtain(null, 3).apply { data = Bundle().apply { putLong("id", id); putString("result", result.toString()) } }) }
                    android.util.Log.i("QingText", "load=${result.optLong("loadMs")} first=${result.optLong("firstMs")} total=${result.optLong("totalMs")} accepted=${result.optBoolean("accepted")}")
                    cancel()
                }
            }
        }
    }
}
