//! 单次录音的取消信号和有界调度状态；识别文字仅由服务 owner 线程持有。
package dev.qingstudy.ime

import android.os.IBinder
import android.os.Messenger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class VoiceSession(val id: Long, val reply: Messenger, val bluetooth: Boolean, val fixture: Boolean) {
    val canceled = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    val partialPending = AtomicBoolean(false)
    val source = AtomicReference<VoiceAudioSource?>(null)
    val recording = AtomicBoolean(false)
    val captureDone = AtomicBoolean(false)
    val committed = StringBuilder()
    var decodeMs = 0L
    var death: IBinder.DeathRecipient? = null

    fun stop() {
        stopped.set(true)
        source.get()?.close()
    }

    fun cancel() {
        canceled.set(true)
        stop()
    }
}
