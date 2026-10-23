//! 在线传输仅发送本次 PCM；短缓冲有上限，不保存录音，不加载本机声学模型。
package dev.qingstudy.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class OnlineSpeechClient(private val context: Context, private val listener: VoiceClient.Listener) : WebSocketListener() {
    private val main = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val readyGate = CountDownLatch(1)
    private val session = UUID.randomUUID().toString()
    @Volatile private var ready = false
    @Volatile private var stopping = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var source: VoiceAudioSource? = null
    private var started = 0L
    @Volatile private var stopped = 0L
    private var first = true
    private val handshakeTimeout = Runnable { fail("连接超时，请检查电脑与网络") }
    private val finalTimeout = Runnable { fail("校正超时，已保留流式识别文字") }
    fun start(bluetooth: Boolean, fixture: Boolean = false) {
        val config = runCatching { OnlineVoiceConfig.read(context) }.getOrElse { fail(it.message ?: "请先配置电脑服务"); return }
        started = SystemClock.elapsedRealtime()
        main.postDelayed(handshakeTimeout, 6000)
        socket = OnlineVoiceProbe.http.newWebSocket(config.request(), this)
        Thread({ capture(bluetooth, fixture) }, "QingOnlineAudio").start()
    }
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (finished.get()) { webSocket.cancel(); return }
        webSocket.send(JSONObject().put("mode", "2pass").put("audio_fs", 16000)
            .put("wav_name", session).toString())
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
        if (finished.get()) return
        if (text.length > 16384) { fail("服务器返回内容过长"); return }
        val data = runCatching { JSONObject(text) }.getOrElse { fail("服务器协议不兼容"); return }
        if (data.has("error")) { fail(OnlineVoiceConfig.message(data.optString("error"))); return }
        if (data.optString("wav_name") != session) { fail("服务器会话不匹配"); return }
        if (data.optString("event") == "ready") {
            ready = true; readyGate.countDown(); main.removeCallbacks(handshakeTimeout)
            post { listener.onState("listening", "电脑在线 · 正在听 · 最长 30 秒") }; return
        }
        if (!data.optBoolean("replace") || !data.has("text")) { fail("服务器结果格式不兼容"); return }
        val words = data.getString("text")
        if (data.optBoolean("is_final")) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                val tail = (SystemClock.elapsedRealtime() - (if (stopped > 0) stopped else started)).coerceAtLeast(0)
                android.util.Log.i("QingOnlineVoice", "finalMs=$tail")
                main.post { listener.onResult(words, tail) }
            }
        } else {
            if (first && words.isNotBlank()) { first = false; android.util.Log.i("QingOnlineVoice", "firstMs=${SystemClock.elapsedRealtime()-started}") }
            post { listener.onPartial(words) }
        }
    }
    private fun capture(bluetooth: Boolean, fixture: Boolean) {
        var audio: VoiceAudioSource? = null
        try {
            audio = if (fixture && BuildConfig.DEBUG) FixtureAudioSource(File(context.filesDir, "debug-voice-input.wav")) else MicrophoneSource(context, bluetooth)
            source = audio
            if (finished.get()) return
            // 松手早于采音线程启动时，也要完成空音频结束握手，不能遗留服务端忙碌会话。
            if (!stopping) try { audio.start() } catch (error: Exception) { if (!stopping) throw error }
            val floats = FloatArray(640)
            val buffered = ByteArrayOutputStream(64000)
            var total = 0
            while (!finished.get() && !stopping && total < 16000 * 30) {
                val count = try { audio.read(floats) } catch (error: Exception) { if (stopping) -1 else throw error }
                if (count <= 0) break
                val pcm = ByteArray(count * 2)
                repeat(count) { i ->
                    val sample = (floats[i].coerceIn(-1f, 1f) * 32767).toInt()
                    pcm[i*2] = sample.toByte(); pcm[i*2+1] = (sample shr 8).toByte()
                }
                total += count
                val level = VoiceLevel.fromPcm(floats, count)
                post { listener.onLevel(level) }
                if (ready) {
                    flush(buffered)
                    send(pcm)
                } else {
                    check(buffered.size() + pcm.size <= 64000) { "连接准备过慢，请稍后重新说话" }
                    buffered.write(pcm)
                }
            }
            if (!finished.get()) {
                check(readyGate.await(5, TimeUnit.SECONDS) && ready) { "连接未就绪，请稍后重试" }
                if (!finished.get()) {
                    flush(buffered)
                    stopped = SystemClock.elapsedRealtime()
                    main.removeCallbacks(handshakeTimeout)
                    main.postDelayed(finalTimeout, 8000)
                    check(socket?.send("{\"is_speaking\":false}") == true) { "连接已断开" }
                    post { listener.onState("processing", "电脑正在校正本次语音…") }
                }
            }
            floats.fill(0f); buffered.reset()
        } catch (error: Exception) {
            if (!finished.get()) fail(if (error is IllegalStateException) error.message ?: "录音不可用" else "麦克风或连接不可用，请重新尝试")
        } finally { runCatching { audio?.close() }; source = null }
    }
    private fun flush(buffer: ByteArrayOutputStream) {
        val bytes = buffer.toByteArray(); buffer.reset()
        for (offset in bytes.indices step 1280) send(bytes.copyOfRange(offset, minOf(offset+1280, bytes.size)))
    }
    private fun send(bytes: ByteArray) {
        if (finished.get()) return
        val target = socket ?: error("连接已断开")
        check(target.queueSize() + bytes.size <= 64000 && target.send(bytes.toByteString())) { "网络传输过慢，已停止本次录音" }
    }
    fun stop() { stopping = true; runCatching { source?.close() } }
    fun cancel() {
        if (finished.compareAndSet(false, true)) { socket?.send("{\"cancel\":true}"); cleanup() }
    }
    fun close() = cancel()
    private fun post(block: () -> Unit) { main.post { if (!finished.get()) block() } }
    private fun fail(message: String) {
        if (finished.compareAndSet(false, true)) { cleanup(); main.post { listener.onState("error", message) } }
    }
    private fun cleanup() {
        stopping = true; readyGate.countDown(); main.removeCallbacks(handshakeTimeout); main.removeCallbacks(finalTimeout)
        runCatching { source?.close() }; socket?.cancel()
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(OnlineVoiceConfig.message("network")) }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); fail(OnlineVoiceConfig.message("network")) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(OnlineVoiceConfig.message("network")) }
}
