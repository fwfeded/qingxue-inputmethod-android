//! IME 主线程客户端；取消立即改变本地会话号，不等待跨进程消息或 native 推理结束。
package dev.qingstudy.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock

class VoiceClient(context: Context, private var listener: Listener) : AutoCloseable {
    interface Listener {
        fun onState(state: String, message: String)
        fun onPartial(text: String)
        fun onResult(text: String, decodeMs: Long)
        fun onMetrics(loadMs: Long, reused: Boolean)
        fun onLevel(level: Float) = Unit
        fun onWarmReady(loadMs: Long) = Unit
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private var session = 0L
    private var pending: Bundle? = null
    private var pendingStop = false
    private var stopping = false
    private var pendingWarm = false
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data
        // 回调可能同步启动下一会话，同一事件始终发给接收时的监听器。
        val eventListener = listener
        if(message.what==VoiceProtocol.WARM_READY) {
            if(!closed)eventListener.onWarmReady(data.getLong("loadMs"))
            return@Handler true
        }
        if (message.what == VoiceProtocol.EVICT && (session == 0L || data.getLong(VoiceProtocol.SESSION) == session)) {
            val wasActive = session != 0L
            session = 0L
            unbind()
            eventListener.onLevel(0f)
            if (wasActive && !closed) eventListener.onState("idle", "已释放本机语音资源")
            return@Handler true
        }
        if (!closed && session != 0L && data.getLong(VoiceProtocol.SESSION) == session) {
            when (message.what) {
                VoiceProtocol.STATE -> {
                    if (stopping && data.getString("state") == "listening") return@Handler true
                    if (data.getString("state") == "error") { session = 0L; eventListener.onLevel(0f) }
                    eventListener.onState(data.getString("state").orEmpty(), data.getString("message").orEmpty())
                }
                VoiceProtocol.PARTIAL -> eventListener.onPartial(data.getString("text").orEmpty())
                VoiceProtocol.RESULT -> { session = 0L; eventListener.onLevel(0f); eventListener.onResult(data.getString("text").orEmpty(), data.getLong("decodeMs")) }
                VoiceProtocol.METRICS -> eventListener.onMetrics(data.getLong("loadMs"), data.getBoolean("reused"))
                VoiceProtocol.LEVEL -> if (!stopping) eventListener.onLevel(data.getFloat("level").coerceIn(0f, 1f))
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || !bound) return
            if (pending == null && session == 0L && !pendingWarm) { unbind(); return }
            remote = Messenger(binder)
            if(pendingWarm) { pendingWarm=false;send(VoiceProtocol.WARM) }
            pending?.let { data ->
                pending = null
                send(VoiceProtocol.START, data)
                if (pendingStop) { pendingStop = false; send(VoiceProtocol.STOP) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    fun start(bluetooth: Boolean = false, sessionListener: Listener? = null) = onMain {
        begin(bluetooth, false, sessionListener)
    }
    fun warmUp() = onMain {
        if(closed||session!=0L)return@onMain
        if(remote!=null) { send(VoiceProtocol.WARM);return@onMain }
        pendingWarm=true
        if(!bound) {
            bound=runCatching { context.bindService(Intent(context,VoiceService::class.java),connection,Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if(!bound)pendingWarm=false
        }
    }

    /** 仅调试 APK 的同 UID 测试入口可主动选择文件输入。 */
    fun startFixtureForTest(sessionListener: Listener? = null) = onMain {
        check(BuildConfig.DEBUG) { "测试音源在发布包中不可用" }
        begin(false, true, sessionListener)
    }

    private fun begin(bluetooth: Boolean, fixture: Boolean, nextListener: Listener?) {
        if (closed) return
        // 在同一主线程操作内先废弃旧会话，再换本次监听器；服务绑定可保留热模型。
        invalidate()
        if (nextListener != null) listener = nextListener
        session = SystemClock.elapsedRealtimeNanos()
        val data = Bundle().apply {
            putLong(VoiceProtocol.SESSION, session)
            putBoolean("bluetooth", bluetooth)
            putBoolean("fixture", fixture)
        }
        listener.onState("loading", "正在准备本机语音…")
        if (remote != null) { send(VoiceProtocol.START, data); return }
        pending = data
        if (!bound) {
            bound = try { context.bindService(Intent(context, VoiceService::class.java), connection, Context.BIND_AUTO_CREATE) }
            catch (_: SecurityException) { false }
            if (!bound) { session = 0L; pending = null; listener.onState("error", "无法启动本机语音服务") }
        }
    }

    fun stop() = onMain {
        if (!closed && session != 0L) {
            stopping = true
            listener.onLevel(0f)
            if (pending != null) pendingStop = true else send(VoiceProtocol.STOP)
        }
    }

    fun cancel() = onMain { invalidate() }

    fun release() = onMain {
        invalidate()
        if (remote != null) send(VoiceProtocol.RELEASE) else bindToRelease()
        unbind()
    }

    override fun close() = onMain {
        if (!closed) { invalidate(); closed = true; unbind() }
    }

    private fun invalidate() {
        val old = session
        session = 0L; pending = null; pendingStop = false; stopping = false; pendingWarm=false
        listener.onLevel(0f)
        if (old != 0L) send(VoiceProtocol.CANCEL, Bundle().apply { putLong(VoiceProtocol.SESSION, old) })
    }

    private fun send(what: Int, data: Bundle = Bundle().apply { putLong(VoiceProtocol.SESSION, session) }) {
        try { remote?.send(Message.obtain(null, what).apply { this.data = data; replyTo = receiver }) }
        catch (_: RemoteException) { disconnected() }
    }

    private fun disconnected() {
        val hadSession = session != 0L
        session = 0L; pending = null; remote = null
        unbind()
        listener.onLevel(0f)
        if (!closed && hadSession) listener.onState("error", "本机语音服务已断开，请重试")
    }

    private fun unbind() {
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }

    private fun bindToRelease() {
        var connected = false
        lateinit var releaseConnection: ServiceConnection
        val cleanup = Runnable {
            if (connected) { connected = false; runCatching { context.unbindService(releaseConnection) } }
        }
        releaseConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching { Messenger(binder).send(Message.obtain(null, VoiceProtocol.RELEASE)) }
                main.removeCallbacks(cleanup)
                cleanup.run()
            }
            override fun onServiceDisconnected(name: ComponentName) { cleanup.run() }
            override fun onNullBinding(name: ComponentName) { cleanup.run() }
            override fun onBindingDied(name: ComponentName) { cleanup.run() }
        }
        connected = runCatching {
            context.bindService(Intent(context, VoiceService::class.java), releaseConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (connected) main.postDelayed(cleanup, 5000)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}
