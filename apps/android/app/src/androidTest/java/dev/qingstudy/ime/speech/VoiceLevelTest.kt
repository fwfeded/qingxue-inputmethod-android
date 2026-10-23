//! 音量由真实样本计算，静音、非数值及异常幅度不得生成虚假波形。
package dev.qingstudy.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    @Test fun pcmSilenceIsZeroAndLouderSamplesIncreaseTheLevel() {
        assertEquals(0f, VoiceLevel.fromPcm(FloatArray(640), 640), 0f)
        val quiet = VoiceLevel.fromPcm(floatArrayOf(.01f, -.01f), 2)
        val loud = VoiceLevel.fromPcm(floatArrayOf(.3f, -.3f), 2)
        assertTrue(quiet > 0f)
        assertTrue(loud > quiet)
        assertTrue(loud <= 1f)
    }
    @Test fun pcmOnlyUsesTheReadRegionAndHandlesInvalidSamples() {
        assertEquals(0f, VoiceLevel.fromPcm(floatArrayOf(0f, 0f, 1f), 2), 0f)
        assertEquals(0f, VoiceLevel.fromPcm(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), 2), 0f)
        assertEquals(1f, VoiceLevel.fromPcm(floatArrayOf(10f, -10f), 2), 0f)
    }
    @Test fun providerDbIsBoundedAndPreservesSignalOrder() {
        assertEquals(0f, VoiceLevel.fromRmsDb(Float.NaN), 0f)
        assertEquals(0f, VoiceLevel.fromRmsDb(-10f), 0f)
        assertTrue(VoiceLevel.fromRmsDb(8f) > VoiceLevel.fromRmsDb(2f))
        assertEquals(1f, VoiceLevel.fromRmsDb(100f), 0f)
    }
}
