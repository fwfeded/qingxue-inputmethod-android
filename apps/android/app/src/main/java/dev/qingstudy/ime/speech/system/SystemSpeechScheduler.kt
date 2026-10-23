//! 系统语音超时使用单调时钟；测试可推进时钟而不等待真实录音。
package dev.qingstudy.ime

internal interface SystemSpeechScheduler {
    fun now(): Long
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}
