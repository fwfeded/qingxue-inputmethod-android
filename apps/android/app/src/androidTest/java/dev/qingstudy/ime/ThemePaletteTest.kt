//! 配色需覆盖实绘控件并维持文字对比；原浅/深配置和系统切换可升级。
package dev.qingstudy.ime

import android.content.res.Configuration
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemePaletteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(c: Int): Double { val v = c / 255.0; return if (v <= .04045) v/12.92 else ((v+.055)/1.055).pow(2.4) }
            return .2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y)+.05)/(minOf(x, y)+.05)
    }
    @Test fun sixThemesHaveReadableKeysCandidatesAndActionLabels() {
        for (id in KeyboardThemes.names.keys) {
            val p = KeyboardPalette(context, KeyboardAppearance(theme = id))
            for ((ink, bg) in listOf(p.ink to p.key, p.ink to p.function, p.muted to p.background,
                p.onAccent to p.accent, p.highlight to p.background)) {
                assertTrue("$id 对比度不足 ${contrast(ink, bg)}", contrast(ink, bg) >= 4.5)
            }
        }
    }
    @Test fun legacyChoicesAndDayNightSelectionArePreserved() {
        val night = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        })
        val day = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        })
        val legacy = KeyboardAppearance.fromJson(JSONObject().put("theme", "system").put("rowHeight", 58))
        assertEquals("dark", KeyboardPalette(night, legacy).themeId)
        assertEquals(58, legacy.rowHeight)
        val selected = legacy.copy(dayTheme = "latte", nightTheme = "nord")
        assertEquals("latte", KeyboardPalette(day, selected).themeId)
        assertEquals("nord", KeyboardPalette(night, selected).themeId)
        assertEquals("dark", KeyboardPalette(day, legacy.copy(theme = "dark")).themeId)
        assertEquals(selected, KeyboardAppearance.fromJson(selected.toJson()))
    }
}
