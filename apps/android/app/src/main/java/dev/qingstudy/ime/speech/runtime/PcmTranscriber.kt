//! 语音引擎边界：只接收本地 PCM 并返回草稿，不读取词库或写入学习记录。
package dev.qingstudy.ime

internal interface PcmTranscriber : AutoCloseable {
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String
}

