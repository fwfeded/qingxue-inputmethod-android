// 候选尺寸回归：长英文不能挤占中文候选，默认键盘应更紧凑且字更大。
package dev.qingstudy.ime

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun adaptiveHeightMigrationAndEmojiBoundaries() {
        val auto = KeyboardAppearance()
        assertEquals(listOf(48, 52, 60, 62), listOf(320, 360, 412, 480).map { auto.resolvedRowHeight(it, false) })
        assertEquals(34, auto.resolvedRowHeight(800, true))
        val previous = KeyboardAppearance.fromJson(JSONObject().put("rowHeight", 58))
        assertFalse(previous.autoHeight)
        assertEquals(58, previous.resolvedRowHeight(360, false))
        for (text in listOf("👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "❤️", "e\u0301", "你")) {
            assertEquals("应一次删除 $text", text.length, EditorDeletion.lastGraphemeLength("测试$text"))
        }
    }

    @Test fun longGlossDoesNotWidenCandidate() {
        val saved = Preferences.read(context)
        try {
            Preferences.save(context, JSONObject())
            instrumentation.runOnMainSync {
                fun measure(gloss: String): CandidateView = CandidateView(context, "你好", gloss).apply {
                    measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                }
                val short = measure("hi")
                val long = measure("hello; good morning; a polite greeting to someone you meet")
                assertEquals("候选宽度必须由中文决定", short.measuredWidth, long.measuredWidth)
                assertTrue("默认候选不应保留旧 76dp 空白", short.measuredHeight / context.resources.displayMetrics.density < 72)
            }
        } finally { Preferences.save(context, saved) }
    }

    @Test fun defaultKeyboardIsCompactWithReadableLetters() {
        val saved = Preferences.read(context)
        try {
            Preferences.save(context, JSONObject())
            instrumentation.runOnMainSync {
                // 默认比例约束使用默认系统字号；大字体完整显示由独立测试覆盖。
                val context = this.context.createConfigurationContext(android.content.res.Configuration(this.context.resources.configuration).apply {
                    fontScale = 1f; orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
                })
                val view = KeyboardView(context) { _, _ -> }
                val density = context.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertTrue("默认键盘内容区应少于 300dp", view.measuredHeight / density < 300)
                val q = descendants(view).filterIsInstance<Button>().first { it.contentDescription == "按键 q" }
                assertTrue("字母字号应至少 23sp", q.textSize / context.resources.displayMetrics.scaledDensity >= 23)
                java.io.File(context.getExternalFilesDir(null), "appearance-metrics.json").writeText(
                    JSONObject().put("heightDp", view.measuredHeight / density)
                        .put("keySp", q.textSize / context.resources.displayMetrics.scaledDensity).toString())
                val landscape = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE; screenWidthDp = 640
                })
                val rotated = KeyboardView(landscape) { _, _ -> }
                fun size(width: Int) { rotated.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), 0) }
                size(640)
                val keyBeforeInsets = descendants(rotated).first { it.contentDescription == "按键 q" }
                size(592)
                assertTrue("横屏安全区变化不应销毁正在点击的按键", keyBeforeInsets === descendants(rotated).first { it.contentDescription == "按键 q" })
            }
        } finally { Preferences.save(context, saved) }
    }

    @Test fun invalidValuesAreBoundedAndLargeFontsFit() {
        val value = KeyboardAppearance.fromJson(JSONObject().put("rowHeight", -100).put("keySize", 1000)
            .put("landscapeHeight", 1000).put("theme", "missing"))
        assertEquals(44, value.rowHeight)
        assertEquals(42, value.landscapeHeight)
        assertEquals(30, value.keySize)
        assertEquals("system", value.theme)
        instrumentation.runOnMainSync {
            val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
            val enlarged = context.createConfigurationContext(config)
            val appearance = KeyboardAppearance(candidateSize = 30, glossSize = 18)
            val candidate = CandidateView(enlarged, "我们一起学英语", "Let us learn English together", appearance = appearance)
            candidate.measure(View.MeasureSpec.makeMeasureSpec((112 * enlarged.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY), 0)
            val paint = android.graphics.Paint()
            fun line(size: Int): Float { paint.textSize = appearance.textPixels(enlarged, size); return paint.fontMetrics.run { bottom - top } }
            assertTrue("放大字体两行应完整容纳", candidate.measuredHeight >= line(30) + line(18) + 2 * enlarged.resources.displayMetrics.density)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
