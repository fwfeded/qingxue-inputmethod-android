//! 防止空格主文字与语音提示在横屏或大字体时重叠，不增加键盘高度。
package dev.qingstudy.ime

import android.content.res.Configuration
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceLabelLayoutTest {
    @Test fun voiceHintDoesNotOverlapSpaceLabelAtPhoneSizes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val saved = Preferences.read(context)
        try {
            Preferences.save(context, JSONObject())
            inst.runOnMainSync {
                for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                    for (font in listOf(1f, 1.3f, 1.5f)) {
                        val config = Configuration(context.resources.configuration).apply {
                            this.orientation = orientation; fontScale = font
                            screenWidthDp = if (orientation == Configuration.ORIENTATION_PORTRAIT) 320 else 570
                        }
                        val target = context.createConfigurationContext(config)
                        val density = target.resources.displayMetrics.density
                        val keyboard = KeyboardView(target) { _, _ -> }
                        keyboard.render(Frame("", emptyList(), "", false))
                        keyboard.measure(View.MeasureSpec.makeMeasureSpec((config.screenWidthDp * density).toInt(), View.MeasureSpec.EXACTLY), 0)
                        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
                        fun descendants(node: View): List<View> = listOf(node) + if (node is ViewGroup)
                            (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
                        val space = descendants(keyboard).filterIsInstance<KeyboardKey>().first { it.contentDescription == "按键 空格" }
                        val layout = space.layout
                        assertEquals("提示和主标签应分行且完整显示", if (space.text.contains('\n')) 2 else 1, layout.lineCount)
                        assertTrue("空格文字必须在键帽内：$orientation/$font",
                            layout.height <= space.height - space.compoundPaddingTop - space.compoundPaddingBottom)
                        if (space.secondary.isNotEmpty()) {
                            val hint = Paint().apply { textSize = minOf(10 * target.resources.displayMetrics.scaledDensity, space.height * .22f) }
                            val hintBottom = 4 * density + hint.fontMetrics.run { bottom - top }
                            val top = space.baseline + space.paint.fontMetrics.top
                            assertTrue("语音提示与空格重叠：$orientation/$font，hintBottom=$hintBottom, labelTop=$top", hintBottom <= top)
                        }
                    }
                }
            }
        } finally { Preferences.save(context, saved) }
    }
}
