//! 私有 Messenger 协议；每条事件携带会话号，接收方忽略已取消会话。
package dev.qingstudy.ime

internal object VoiceProtocol {
    const val START = 1
    const val STOP = 2
    const val CANCEL = 3
    const val RELEASE = 4
    const val WARM = 5
    const val STATE = 11
    const val PARTIAL = 12
    const val RESULT = 13
    const val METRICS = 14
    const val EVICT = 15
    const val LEVEL = 16
    const val WARM_READY = 17
    const val SESSION = "session"
}
