// R4 设置迁移与真实 WindowInsets 分开验证，手动留白不能覆盖系统安全区。
package dev.qingstudy.ime

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class R4AppearanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    @Test fun extraBottomSpaceUpdatesImmediatelyWithoutAddingInsetsTwice() {
        val saved = Preferences.read(context)
        try {
            Preferences.save(context, JSONObject().put("keep-test-data", "unchanged"))
            instrumentation.runOnMainSync {
                val view = KeyboardView(context) { _, _ -> }
                val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                    .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 80)).build()
                view.dispatchApplyWindowInsets(insets)
                KeyboardAppearance.save(context, KeyboardAppearance(bottomSpace = 24))
                view.reloadAppearance()
                assertEquals("调整后无需等待新的系统 inset", dp(28) + 80, view.paddingBottom)
                view.dispatchApplyWindowInsets(insets)
                assertEquals("不能重复叠加安全区", dp(28) + 80, view.paddingBottom)
                KeyboardAppearance.save(context, KeyboardAppearance())
                view.reloadAppearance()
                assertEquals("恢复默认保留系统安全区", dp(4) + 80, view.paddingBottom)
                assertEquals("unchanged", Preferences.read(context).getString("keep-test-data"))
            }
        } finally { Preferences.save(context, saved) }
    }
    @Test fun optionalNumberRowAndPanelsHaveSameMeasuredHeight() {
        val saved = Preferences.read(context)
        try {
            KeyboardAppearance.save(context, KeyboardAppearance(numberRow = true))
            instrumentation.runOnMainSync {
                val view = KeyboardView(context) { _, _ -> }
                fun measure(): Int { view.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY), 0); return view.measuredHeight }
                view.render(Frame("", emptyList(), "", false))
                val letters = measure()
                view.setNumberLayout(true); assertEquals(letters, measure())
                view.render(Frame("", listOf("，" to "comma"), "", false, panel = "符号 · 常用", panelSections = listOf("常用")))
                assertEquals(letters, measure())
                view.render(Frame("", emptyList(), "", false, scheme = "大千注音"))
                view.setNumberLayout(false)
                val phonetic = measure()
                view.setNumberLayout(true); assertEquals(phonetic, measure())
                view.render(Frame("", listOf("，" to "comma"), "", false, scheme = "大千注音", panel = "符号 · 常用", panelSections = listOf("常用")))
                assertEquals("注音进入面板也应等高", phonetic, measure())
            }
        } finally { Preferences.save(context, saved) }
    }
    @Test fun appearanceMigrationBoundsNewControlsAndPreservesManualHeight() {
        val value = KeyboardAppearance.fromJson(JSONObject().put("rowHeight", 58).put("bottomSpace", 200)
            .put("keyGap", -5).put("keyRadius", 100).put("candidateSize", 22))
        assertFalse(value.autoHeight); assertEquals(58, value.resolvedRowHeight(412, false))
        assertEquals(36, value.bottomSpace); assertEquals(3, value.keyGap); assertEquals(12, value.keyRadius)
        assertEquals(22, value.candidateSize)
        assertEquals(value, KeyboardAppearance.fromJson(value.toJson()))
    }
    @Test fun largeFontBottomKeysKeepAlignedFullTouchCells() {
        val saved = Preferences.read(context)
        try {
            Preferences.save(context, JSONObject())
            instrumentation.runOnMainSync {
                val enlarged = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f })
                val view = KeyboardView(enlarged) { _, _ -> }
                view.render(Frame("", emptyList(), "", false))
                view.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY), 0)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                fun descendants(node: View): List<View> = listOf(node) + if (node is android.view.ViewGroup)
                    (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
                val bottomKeys = listOf("按键 符号", "按键 123", "按键 ，", "按键 空格", "工具 english", "按键 回车")
                val bounds = bottomKeys.map { name ->
                    val key = descendants(view).first { it.contentDescription == name }
                    android.graphics.Rect().also { key.getDrawingRect(it); view.offsetDescendantRectToMyCoords(key, it) }
                }
                assertEquals("不同字号不能让底行按键错位", 1, bounds.map { it.top }.distinct().size)
                assertEquals(1, bounds.map { it.bottom }.distinct().size)
                assertTrue("触点必须完整留在键盘内容区", bounds.all { it.bottom <= view.height - view.paddingBottom })
            }
        } finally { Preferences.save(context, saved) }
    }
}
