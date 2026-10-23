// 真机反馈回归：系统子项名称、手势留白与常驻收起入口。
package dev.qingstudy.ime

import android.content.res.Configuration
import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PhoneSystemUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE && (view.parent as? View)?.let(::visible) != false
    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY), 0)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun insets(nav: Int, gesture: Int, left: Int = 0) = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(left, 0, 0, nav))
        .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, gesture))
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 900)).build()

    @Test fun subtypeNameIncludesBrandInChineseAndEnglishSystemLocales() {
        val ime = context.getSystemService(InputMethodManager::class.java).inputMethodList.first { it.packageName == context.packageName }
        assertEquals("青学输入法", ime.loadLabel(context.packageManager).toString())
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            assertEquals("子项不能仅显示语言名：$locale", "青学输入法",
                ime.getSubtypeAt(0).getDisplayName(localized, context.packageName, context.applicationInfo).toString())
        }
    }

    @Test fun hiddenNavigationBarStillReservesMandatoryGestureArea() {
        instrumentation.runOnMainSync {
            val view = KeyboardView(context) { _, _ -> }
            view.render(Frame("", emptyList(), "", false))
            view.dispatchApplyWindowInsets(insets(0, 80))
            measure(view)
            assertEquals("导航栏为零时仍要留出系统底部区域", dp(4) + 80, view.paddingBottom)
            val symbol = descendants(view).first { it.contentDescription == "按键 符号" }
            val location = android.graphics.Rect()
            symbol.getDrawingRect(location); view.offsetDescendantRectToMyCoords(symbol, location)
            assertTrue("符号键必须完整位于安全区域之上", location.bottom <= view.height - 80)
        }
    }

    @Test fun largerNavigationInsetIsNotSummedOrLeftStale() {
        instrumentation.runOnMainSync {
            val view = KeyboardView(context) { _, _ -> }
            repeat(2) { view.dispatchApplyWindowInsets(insets(100, 40, 70)) }
            assertEquals(dp(4) + 100, view.paddingBottom)
            assertEquals(dp(5) + 70, view.paddingLeft)
            view.dispatchApplyWindowInsets(insets(0, 0))
            assertEquals("系统区域收回后不残留抬高", dp(4), view.paddingBottom)
            assertEquals(dp(5), view.paddingLeft)
        }
    }

    @Test fun childInsetsAreNotDiscardedWhenRootReportsLess() {
        var keyboard: KeyboardView? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> keyboard = KeyboardView(activity) { _, _ -> }; activity.setContentView(keyboard) }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val view = keyboard!!
                assertNotNull("测试必须连接真实窗口", view.rootWindowInsets)
                val rootBottom = view.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.mandatorySystemGestures()).bottom
                view.dispatchApplyWindowInsets(insets(rootBottom + 60, 0))
                assertEquals("根窗口不能覆盖子视图收到的更大安全区", dp(4) + rootBottom + 60, view.paddingBottom)
            }
        }
    }

    @Test fun hideButtonRemainsAvailableOnIdleCandidatesAndSymbols() {
        instrumentation.runOnMainSync {
            var count = 0
            val view = KeyboardView(context) { key, _ -> if (key == "hide") count++ }
            val frames = listOf(Frame("", emptyList(), "", false),
                Frame("nihao", listOf("你好" to "hello"), "", false),
                Frame("", listOf("，" to "comma"), "", false, panel = "符号 · 常用", panelSections = listOf("常用")))
            for (frame in frames) {
                view.render(frame); measure(view)
                descendants(view).filterIsInstance<android.widget.TextView>().filter { it.text == "123" && visible(it) }.forEach {
                    assertTrue("工具数字图标不能裁字", it.paint.measureText(it.text.toString()) <= it.width - it.paddingLeft - it.paddingRight)
                }
                val controls = descendants(view).filter { it.contentDescription == "收起键盘" && visible(it) }
                assertEquals("每种状态右上角都应有一个收起入口", 1, controls.size)
                assertTrue(controls.single().performClick())
            }
            assertEquals(3, count)
        }
    }
}
