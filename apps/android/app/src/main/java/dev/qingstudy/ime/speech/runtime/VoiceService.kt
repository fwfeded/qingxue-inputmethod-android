//! 独立进程语音服务；模型只在 owner 线程加载、解码和关闭，录音线程不触碰模型。
package dev.qingstudy.ime

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val ownerThread = HandlerThread("qingstudy-voice-model", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var owner: Handler
    private val active = AtomicReference<VoiceSession?>(null)
    private var engine: SenseVoiceTranscriber? = null
    private var epoch = 0L
    private var destroyed = false
    private val clients = mutableMapOf<IBinder, Pair<Messenger, Long>>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        // exported=false 之外再验证 UID，调试音源也不接受外部应用调用。
        if (message.sendingUid == Process.myUid()) handle(message)
        true
    })
    private val idle = Runnable { releaseEngine(epoch) }

    override fun onCreate() {
        super.onCreate()
        currentService = this
        ownerThread.start()
        owner = Handler(ownerThread.looper)
        scheduleIdle()
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        cancelActive()
        scheduleIdle()
        return false
    }

    private fun handle(message: Message) {
        when (message.what) {
            VoiceProtocol.START -> {
                val reply = message.replyTo ?: return
                cancelActive()
                main.removeCallbacks(idle)
                epoch++
                val data = message.data
                val id = data.getLong(VoiceProtocol.SESSION)
                if (id == 0L) return
                val fixture = BuildConfig.DEBUG && (data.getBoolean("fixture") || File(filesDir, "debug-voice-enabled").isFile)
                val session = VoiceSession(id, reply, data.getBoolean("bluetooth"), fixture)
                clients[reply.binder] = reply to id
                active.set(session)
                val death = IBinder.DeathRecipient { main.post {
                    clients.remove(reply.binder)
                    if (isActive(session)) { cancelActive(); scheduleIdle() }
                } }
                session.death = death
                try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { cancelActive(); scheduleIdle(); return }
                post(session) { prepare(session) }
                // 预热可能占用 owner；音源独立启动，不能让已经开始说话的用户等模型。
                state(session,"loading","正在准备本机语音…")
                Thread({ capture(session) }, "qingstudy-voice-capture").start()
            }
            VoiceProtocol.WARM -> {
                val reply=message.replyTo ?: return
                if(active.get()!=null)return
                epoch++
                clients[reply.binder]=reply to 0L
                main.removeCallbacks(idle)
                owner.post {
                    val began=SystemClock.elapsedRealtime()
                    runCatching {
                        if(!destroyed && engine==null && VoiceModels.isReady(this)) VoiceModels.locked(this,{ destroyed }) {
                            if(engine==null&&VoiceModels.isReady(this))engine=SenseVoiceTranscriber(VoiceModels.directory(this),2)
                        }
                        if(engine!=null)reply.send(Message.obtain(null,VoiceProtocol.WARM_READY).apply { data=Bundle().apply { putLong("loadMs",SystemClock.elapsedRealtime()-began) } })
                    }
                    main.post { scheduleIdle() }
                }
            }
            VoiceProtocol.STOP -> active.get()?.takeIf { it.id == message.data.getLong(VoiceProtocol.SESSION) }?.let {
                it.stop()
                event(it, VoiceProtocol.LEVEL) { putFloat("level", 0f) }
                state(it, "processing", "正在整理识别结果…")
            }
            VoiceProtocol.CANCEL -> active.get()?.takeIf { it.id == message.data.getLong(VoiceProtocol.SESSION) }?.let {
                cancelActive(); scheduleIdle()
            }
            VoiceProtocol.RELEASE -> { cancelActive(); releaseEngine(++epoch) }
        }
    }

    private fun prepare(session: VoiceSession) {
        try {
            if (!isActive(session)) return
            if (!session.fixture) {
                check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "请先在语音设置中允许麦克风权限"
                }
                if (session.bluetooth) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        check(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            "请先在语音设置中允许蓝牙连接权限"
                        }
                    } else {
                        error("蓝牙语音输入需要 Android 12 或以上版本")
                    }
                }
            }
            if (!VoiceModels.isReady(this)) {
                engine?.close(); engine = null
                error("请先在语音设置中下载离线语音包")
            }
            if (!isActive(session)) return
            val reused = engine != null
            val start = SystemClock.elapsedRealtime()
            // 私有包准备完即可采集；有界音频任务排在本次加载后处理，不丢冷启动时的开头。
            // 麦克风成功启动后才发 listening；推理仍只有 owner 一个线程。
            if (!reused) {
                VoiceModels.locked(this, { !isActive(session) }) {
                    check(VoiceModels.isReady(this)) { "语音包已删除，请重新准备" }
                    engine = SenseVoiceTranscriber(VoiceModels.directory(this), 2)
                }
            }
            if (!isActive(session)) return
            event(session, VoiceProtocol.METRICS) {
                putLong("loadMs", SystemClock.elapsedRealtime() - start); putBoolean("reused", reused)
            }
            // 停止/结束由采集线程排队，保证末段音频先识别，不能在此提前返回空结果。
        } catch (_: CancellationException) {
            // 取消是正常控制流；本地与远端的会话代号都会阻断迟到事件。
        } catch (failure: Throwable) { fail(session, failure) }
    }

    private fun capture(session: VoiceSession) {
        val segment = FloatArray(SEGMENT_SAMPLES)
        val chunk = FloatArray(640)
        var count = 0
        var total = 0
        var nextPartial = FIRST_PARTIAL_SAMPLES
        var nextLevelAt = 0L
        try {
            if(!session.fixture)check(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
            check(VoiceModels.isReady(this)) { "请先下载离线语音包" }
            val source: VoiceAudioSource = if (session.fixture) FixtureAudioSource(File(filesDir, "debug-voice-input.wav"))
                else MicrophoneSource(this, session.bluetooth)
            session.source.set(source)
            if (!isActive(session) || session.stopped.get()) {
                source.close(); if (isActive(session)) post(session) { finish(session) }; return
            }
            source.start()
            if (!isActive(session) || session.stopped.get()) {
                source.close(); if (isActive(session)) post(session) { finish(session) }; return
            }
            session.recording.set(true)
            state(session, "listening", if (session.bluetooth && !session.fixture) "正在确认蓝牙麦克风…" else "正在聆听…")
            while (isActive(session) && !session.stopped.get() && total < MAX_SAMPLES) {
                val read = source.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                val now = SystemClock.elapsedRealtime()
                if (now >= nextLevelAt && !session.stopped.get()) {
                    event(session, VoiceProtocol.LEVEL) { putFloat("level", VoiceLevel.fromPcm(chunk, read)) }
                    nextLevelAt = now + 100
                }
                if (total == 0 && session.bluetooth && !session.fixture) state(session, "listening", "正在使用蓝牙麦克风")
                var offset = 0
                while (offset < read && total < MAX_SAMPLES) {
                    val take = minOf(read - offset, SEGMENT_SAMPLES - count, MAX_SAMPLES - total)
                    chunk.copyInto(segment, count, offset, offset + take)
                    count += take; total += take; offset += take
                    if (count == SEGMENT_SAMPLES) {
                        enqueue(session, segment.copyOf(), commit = true)
                        count = 0; nextPartial = FIRST_PARTIAL_SAMPLES
                    } else if (count >= nextPartial && session.partialPending.compareAndSet(false, true)) {
                        enqueue(session, segment.copyOf(count), commit = false)
                        // 首次提前，后续仍间隔四秒；短句不在结束前密集重识别。
                        nextPartial = count+PARTIAL_SAMPLES
                    }
                }
            }
            if (isActive(session)) {
                session.captureDone.set(true)
                event(session, VoiceProtocol.LEVEL) { putFloat("level", 0f) }
                state(session, "processing", if (total >= MAX_SAMPLES) "已达到 60 秒上限，正在完成识别…" else "正在完成本机识别…")
                if (count > 0) enqueue(session, segment.copyOf(count), commit = true)
                post(session) { finish(session) }
            }
        } catch (failure: Throwable) {
            if (isActive(session) && session.stopped.get()) {
                session.captureDone.set(true)
                if (count > 0) enqueue(session, segment.copyOf(count), commit = true)
                post(session) { finish(session) }
            } else fail(session, failure)
        } finally {
            session.recording.set(false)
            event(session, VoiceProtocol.LEVEL) { putFloat("level", 0f) }
            session.source.getAndSet(null)?.close()
            segment.fill(0f); chunk.fill(0f)
        }
    }

    private fun enqueue(session: VoiceSession, samples: FloatArray, commit: Boolean) {
        post(session) {
            try {
                if (!isActive(session) || (!commit && (session.stopped.get()||session.captureDone.get()))) return@post
                val start = SystemClock.elapsedRealtime()
                val text = engine?.transcribe(samples)?.trim() ?: error("识别模型未就绪")
                session.decodeMs += SystemClock.elapsedRealtime() - start
                if (!isActive(session)) return@post
                if (commit) session.committed.append(text)
                val draft = if (commit) session.committed.toString() else session.committed.toString() + text
                if (draft.isNotBlank()) event(session, VoiceProtocol.PARTIAL) { putString("text", draft) }
            } catch (failure: Throwable) { fail(session, failure) }
            finally { samples.fill(0f); if (!commit) session.partialPending.set(false) }
        }
    }

    private fun finish(session: VoiceSession) {
        if (!isActive(session)) return
        val text = session.committed.toString().trim()
        if (text.isBlank()) { fail(session, IllegalStateException("没有听到清晰语音，请靠近麦克风重试")); return }
        event(session, VoiceProtocol.RESULT) { putString("text", text); putLong("decodeMs", session.decodeMs) }
        main.post { if (active.compareAndSet(session, null)) { unlink(session); scheduleIdle() } }
    }

    private fun fail(session: VoiceSession, failure: Throwable) {
        if (!isActive(session)) return
        val message = when (failure) {
            is SecurityException -> "麦克风或蓝牙权限未授予，请在语音设置中允许权限"
            is OutOfMemoryError -> "设备可用内存不足，请释放语音资源后重试"
            is IllegalStateException -> failure.message ?: "本机语音暂时不可用，请重试"
            else -> "本机语音启动或识别失败，请检查语音包后重试"
        }
        state(session, "error", message)
        session.cancel()
        main.post { if (active.compareAndSet(session, null)) { unlink(session); scheduleIdle() } }
    }

    private fun state(session: VoiceSession, state: String, message: String) = event(session, VoiceProtocol.STATE) {
        putString("state", state); putString("message", message)
    }

    private fun event(session: VoiceSession, what: Int, fill: Bundle.() -> Unit) {
        if (!isActive(session)) return
        val data = Bundle().apply { putLong(VoiceProtocol.SESSION, session.id); fill() }
        try { session.reply.send(Message.obtain(null, what).apply { this.data = data }) }
        catch (_: Exception) { session.cancel(); main.post { if (active.compareAndSet(session, null)) { unlink(session); scheduleIdle() } } }
    }

    private fun isActive(session: VoiceSession): Boolean = active.get() === session && !session.canceled.get()

    private fun cancelActive() {
        active.getAndSet(null)?.let {
            it.cancel(); owner.removeCallbacksAndMessages(it); unlink(it)
        }
    }

    private fun post(session: VoiceSession, action: () -> Unit) {
        if (isActive(session)) owner.postAtTime(action, session, SystemClock.uptimeMillis())
    }

    private fun unlink(session: VoiceSession) {
        session.death?.let { runCatching { session.reply.binder.unlinkToDeath(it, 0) } }
        session.death = null
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idle)
        if (!destroyed && active.get() == null) main.postDelayed(idle, 60_000)
    }

    private fun releaseEngine(expectedEpoch: Long) {
        main.removeCallbacks(idle)
        owner.post {
            if (active.get() != null) return@post
            engine?.close(); engine = null
            main.post {
                if (active.get() == null && epoch == expectedEpoch) {
                    clients.values.forEach { (reply, id) ->
                        runCatching { reply.send(Message.obtain(null, VoiceProtocol.EVICT).apply {
                            data = Bundle().apply { putLong(VoiceProtocol.SESSION, id) }
                        }) }
                    }
                    clients.clear()
                    main.postDelayed({ if (active.get() == null && epoch == expectedEpoch) exitPrivateProcess() }, 150)
                }
            }
        }
    }

    private fun exitPrivateProcess() {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (name == "$packageName:voice") Process.killProcess(Process.myPid())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            active.get()?.let { state(it, "error", "系统内存紧张，已释放本机语音，请重试") }
            cancelActive(); releaseEngine(++epoch)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        cancelActive(); releaseEngine(++epoch)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(idle)
        cancelActive()
        owner.post {
            engine?.close(); engine = null
            ownerThread.quitSafely()
            main.post { if (currentService === this) exitPrivateProcess() }
        }
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentService: VoiceService? = null
        private const val SEGMENT_SAMPLES = 16000 * 15
        private const val PARTIAL_SAMPLES = 16000 * 4
        private const val FIRST_PARTIAL_SAMPLES = 16000 * 12 / 10
        private const val MAX_SAMPLES = 16000 * 60
    }
}
