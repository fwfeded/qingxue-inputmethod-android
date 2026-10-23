//! 不同松手时机下的连续真实触屏输入；保留输入框原文和英文候选断言。
package dev.qingstudy.ime

import android.os.SystemClock
import android.util.Log
import org.junit.Test

class VoiceHandoffRepeatTest : VoiceGestureV4Test() {
    @Test fun immediateTypingAtDifferentReleaseTimes() {
        for (round in 0 until 12) {
            val delay = listOf(0L, 80L, 250L, 600L, 1000L, 1400L)[round % 6]
            Log.i("QingHandoffTest", "round=$round releaseDelay=$delay")
            typeAfterRelease(delay, rapid = true)
            SystemClock.sleep(150)
        }
    }
}
