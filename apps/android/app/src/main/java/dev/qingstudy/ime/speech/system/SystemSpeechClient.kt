//! 手机已有语音服务的主线程会话边界。
package dev.qingstudy.ime

import android.speech.RecognitionListener
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class SystemSpeechClient(
    private val listener: VoiceClient.Listener,
    private val available: () -> Boolean,
    private val createDriver: (RecognitionListener) -> SystemSpeechDriver,
    private val scheduler: SystemSpeechScheduler,
    private val reportError: (Int) -> Unit = {}
) : AutoCloseable {
    constructor(context: Context, listener: VoiceClient.Listener) : this(
        listener, { isAvailable(context) },
        { callback -> AndroidSpeechDriver(context.applicationContext, callback) }, HandlerSpeechScheduler(),
        { code -> SystemSpeechDiagnostics.record(context.applicationContext, code) }
    )

    private val main = Handler(Looper.getMainLooper())
    private var driver: SystemSpeechDriver? = null
    private var generation = 0L
    private var active = false
    private var ready = false
    private var stopped = false
    private var closed = false
    private var began = 0L
    private val pending = mutableListOf<Runnable>()

    fun start(punctuation: String) = onMain {
        if (closed) return@onMain
        dispose(cancel = true)
        if (!runCatching { available() }.getOrDefault(false)) {
            listener.onState("error", "手机未提供语音服务，请在语音设置中选择并下载离线语音包")
            return@onMain
        }
        active = true; ready = false; stopped = false; began = scheduler.now()
        val token = ++generation
        listener.onState("loading", "正在连接手机语音服务…")
        if (!current(token)) return@onMain
        later(token, 10_000) { fail(token, "手机语音服务未就绪，请重试或在语音设置中选择离线语音") }
        try {
            driver = createDriver(callback(token))
            driver?.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (punctuation == "model" && Build.VERSION.SDK_INT >= 33) {
                    // 请求不代表提供方保证支持；其结果按约定将格式化文本放在原文前。
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
                }
            })
        } catch (error: Exception) { fail(token, startupError(error)) }
    }

    fun stop() = onMain { stop(generation, "正在等待手机语音识别结果…") }

    fun cancel() = onMain { dispose(cancel = true) }

    override fun close() = onMain {
        if (!closed) { closed = true; dispose(cancel = true) }
    }

    private fun callback(token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onMain {
            if (!current(token) || stopped || ready) return@onMain
            ready = true
            clearTasks()
            later(token, 60_000) { stop(token, "已达到 60 秒上限，正在完成识别…") }
            listener.onMetrics(scheduler.now() - began, false)
            listener.onState("listening", "正在聆听 · 手机语音服务")
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onRmsChanged(rmsdB: Float) = onMain {
            if (current(token) && ready && !stopped) listener.onLevel(VoiceLevel.fromRmsDb(rmsdB))
        }
        override fun onPartialResults(partialResults: Bundle?) = onMain {
            if (current(token)) bestText(partialResults)?.let { listener.onPartial(it) }
        }
        override fun onEndOfSpeech() = onMain {
            if (current(token) && !stopped) waitForResult(token, "正在等待手机语音识别结果…")
        }
        override fun onResults(results: Bundle?) = onMain {
            if (!current(token)) return@onMain
            val text = bestText(results)
            if (text == null) { fail(token, "没有听到清晰语音，请靠近麦克风重试"); return@onMain }
            dispose(cancel = false)
            // 系统 API 不提供真实解码耗时，不把会话时长伪称为推理耗时。
            listener.onResult(text, 0)
        }
        override fun onError(error: Int) = onMain {
            if (current(token)) {
                reportError(error)
                fail(token, "${errorMessage(error)}（Android 错误码 $error）")
            }
        }
    }

    private fun stop(token: Long, message: String) {
        if (!current(token) || stopped) return
        waitForResult(token, message)
        try { driver?.stop() } catch (error: Exception) { fail(token, startupError(error)) }
    }

    private fun waitForResult(token: Long, message: String) {
        stopped = true
        clearTasks()
        later(token, 8_000) { fail(token, "手机语音服务未及时返回结果，请重试或在语音设置中选择离线语音") }
        listener.onLevel(0f)
        listener.onState("processing", message)
    }

    private fun fail(token: Long, message: String) {
        if (!current(token)) return
        dispose(cancel = false)
        listener.onState("error", message)
    }

    private fun current(token: Long) = active && !closed && generation == token

    private fun later(token: Long, delayMs: Long, action: () -> Unit) {
        val task = Runnable { if (current(token)) action() }
        pending += task
        scheduler.postDelayed(task, delayMs)
    }

    private fun clearTasks() { pending.forEach(scheduler::remove); pending.clear() }

    private fun dispose(cancel: Boolean) {
        active = false; generation++; ready = false; stopped = false
        clearTasks()
        val old = driver
        driver = null
        // 先废弃代号：提供方可能在 cancel/destroy 期间同步或异步回调。
        if (cancel) runCatching { old?.cancel() }
        runCatching { old?.close() }
        listener.onLevel(0f)
    }

    private fun bestText(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.trim()

    private fun startupError(error: Exception) = if (error is SecurityException)
        "麦克风权限未授予，请在语音设置中允许权限" else "手机语音服务启动失败，请重试或在语音设置中选择离线语音"

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未授予，请在语音设置中允许权限"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到清晰语音，请靠近麦克风重试"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "手机语音服务网络不可用，请检查网络或在语音设置中选择离线语音"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "手机语音服务正忙，请稍后重试"
        SpeechRecognizer.ERROR_AUDIO -> "手机语音服务无法使用麦克风，请检查是否被其他应用占用"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "手机语音服务暂不支持中文，请检查系统语音语言包或在语音设置中选择离线语音"
        else -> "手机语音识别失败，请重试或在语音设置中选择离线语音"
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        fun isAvailable(context: Context): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }
}
