//! 对比真实绘制像素，确保实际音量能改变波形且停止后回到静态基线。
package dev.qingstudy.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWaveformTest {
    @Test fun audioLevelChangesPixelsAndSilenceResetsTheWaveform() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = VoiceWaveformView(instrumentation.targetContext, android.graphics.Color.BLUE)
            view.layout(0, 0, 160, 60)
            fun pixels(): Bitmap = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            val silent = pixels()
            view.level(.8f)
            val speaking = pixels()
            assertFalse("音量输入必须改变实际画面", silent.sameAs(speaking))
            view.level(0f)
            val stopped = pixels()
            assertTrue("停止后不能保留虚假的说话波形", silent.sameAs(stopped))
            silent.recycle(); speaking.recycle(); stopped.recycle()
        }
    }
}
