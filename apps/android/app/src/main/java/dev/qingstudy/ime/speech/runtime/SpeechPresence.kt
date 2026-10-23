//! ASR 前检测真实语音，防止静音/纯噪声产生幻觉文本；不裁剪有效语音内容。
package dev.qingstudy.ime

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

internal class SpeechPresence(model: File) : AutoCloseable {
    private val detector = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = model.absolutePath, threshold = 0.5f, minSilenceDuration = 0.25f,
            minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 20f,
        ),
        sampleRate = 16000, numThreads = 1, provider = "cpu",
    ))

    fun containsSpeech(samples: FloatArray): Boolean = try {
        detector.acceptWaveform(samples)
        detector.flush()
        !detector.empty()
    } finally { detector.reset(); detector.clear() }

    override fun close() { detector.release() }
}

