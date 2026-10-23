//! 固定 CPU 推理；模型从私有目录加载，流在每次识别后释放，实例由单个后台线程持有。
package dev.qingstudy.ime

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

internal class SenseVoiceTranscriber(directory: File, threads: Int = 2) : PcmTranscriber {
    private val owner = Thread.currentThread().id
    private var closed = false
    private val recognizer: OfflineRecognizer
    private val presence: SpeechPresence

    init {
        val started=android.os.SystemClock.elapsedRealtime()
        val cpuStarted=android.os.Process.getElapsedCpuTime()
        require(threads in 1..4) { "线程数应为 1–4" }
        val model = File(directory, "model.int8.onnx")
        val tokens = File(directory, "tokens.txt")
        val vad = File(directory, "silero_vad.onnx")
        require(model.isFile && tokens.isFile && vad.isFile) { "缺少 SenseVoice 模型、词元或 VAD 文件" }
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath, language = "auto", useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath, numThreads = threads, provider = "cpu", debug = false,
            ),
        ))
        val asrReady=android.os.SystemClock.elapsedRealtime()
        presence = try { SpeechPresence(vad) } catch (failure: Throwable) {
            recognizer.release()
            throw failure
        }
        android.util.Log.i("QingVoiceInit","asrMs=${asrReady-started} vadMs=${android.os.SystemClock.elapsedRealtime()-asrReady} cpuMs=${android.os.Process.getElapsedCpuTime()-cpuStarted}")
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        check(Thread.currentThread().id == owner && !closed) { "引擎已关闭或调用线程错误" }
        require(sampleRate == 16000) { "本轮仅支持 16kHz 单声道 PCM" }
        require(samples.isNotEmpty() && samples.size <= sampleRate * 20) { "音频应为 0–20 秒" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "PCM 值无效" }
        if (!presence.containsSpeech(samples)) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally { stream.release() }
    }

    override fun close() {
        check(Thread.currentThread().id == owner) { "应在创建引擎的线程释放" }
        if (!closed) { presence.close(); recognizer.release(); closed = true }
    }
}

