//! 主线程的单调计时器，用于系统语音启动、录音和结束超时。
package dev.qingstudy.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class HandlerSpeechScheduler : SystemSpeechScheduler {
    private val main = Handler(Looper.getMainLooper())
    override fun now() = SystemClock.elapsedRealtime()
    override fun postDelayed(task: Runnable, delayMs: Long) { main.postDelayed(task, delayMs) }
    override fun remove(task: Runnable) { main.removeCallbacks(task) }
}
