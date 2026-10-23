//! 隔离系统识别器的创建与释放，便于验证迟到回调和失败恢复。
package dev.qingstudy.ime

import android.content.Intent

internal interface SystemSpeechDriver : AutoCloseable {
    fun start(intent: Intent)
    fun stop()
    fun cancel()
}
