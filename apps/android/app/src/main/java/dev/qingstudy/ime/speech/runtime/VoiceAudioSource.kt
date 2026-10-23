//! 录音与调试 PCM 的统一输入边界；只在内存中传递单声道 16kHz 样本。
package dev.qingstudy.ime

internal interface VoiceAudioSource : AutoCloseable {
    fun start()
    fun read(destination: FloatArray): Int
}
