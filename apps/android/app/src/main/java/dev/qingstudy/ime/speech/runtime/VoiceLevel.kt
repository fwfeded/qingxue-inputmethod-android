//! 真实音频的归一化显示音量；不生成与麦克风无关的随机波形。
package dev.qingstudy.ime

import kotlin.math.log10
import kotlin.math.sqrt

internal object VoiceLevel {
    fun fromPcm(samples: FloatArray, count: Int): Float {
        val size = count.coerceIn(0, samples.size)
        if (size == 0) return 0f
        var power = 0.0
        for (index in 0 until size) {
            val value = samples[index]
            if (value.isFinite()) { val bounded = value.coerceIn(-1f, 1f).toDouble(); power += bounded * bounded }
        }
        val rms = sqrt(power / size)
        if (rms <= 0.001) return 0f
        return ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    // 提供方的 dB 尺度可能不同，此区间仅用于有界显示，不当作声压测量。
    fun fromRmsDb(rmsDb: Float): Float = if (rmsDb.isFinite()) ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) else 0f
}
