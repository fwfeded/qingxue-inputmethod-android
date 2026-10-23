// Core 分类目录的触屏面板；条目及分类均由 Rust 提供，固定高度内滚动。
package dev.qingstudy.ime

import android.content.Context
import android.util.TypedValue
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

class SymbolPanelView(context: Context, frame: Frame, rowHeight: Int, private val palette: KeyboardPalette, private val pending: Boolean,
    private val action: (String, String) -> Unit, areaHeight: Int = rowHeight * 4) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, description: String, color: Int, size: Int = 18, click: () -> Unit) = KeyboardKey(context).apply {
        text = label; contentDescription = description; isAllCaps = false; includeFontPadding = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(dp(4), 0, dp(4), 0)
        setAutoSizeTextTypeUniformWithConfiguration(10, size, 1, TypedValue.COMPLEX_UNIT_SP)
        setTextColor(if (color == palette.accent) palette.onAccent else palette.ink); background = palette.surface(color, true); stateListAnimator = null
        feedbackKind = when (description) { "按键 ⌫" -> "delete"; "按键 空格", "按键 回车" -> "action"; else -> "letter" }
        isEnabled = !pending; setOnClickListener { click() }
        if (description == "按键 ⌫") { repeatAction = click; canRepeat = { isEnabled } }
    }
    init {
        orientation = VERTICAL
        val categories = LinearLayout(context).apply { isBaselineAligned = false }
        frame.panelSections.forEachIndexed { index, name ->
            categories.addView(button(name, "分类 $name", if (index == frame.panelCategory) palette.accent else palette.background, 14) {
                action("panel-category", index.toString())
            }, LayoutParams(-2, dp(36)).apply { width = dp(maxOf(56, name.length * 15 + 16)) })
        }
        val categoryScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(categories) }
        addView(categoryScroll, LayoutParams(-1, dp(36)))
        categoryScroll.post { categories.getChildAt(frame.panelCategory)?.let { categoryScroll.scrollTo(it.left, 0) } }
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        val columns = frame.panelColumns.coerceIn(3, 6)
        frame.candidates.chunked(columns).forEachIndexed { line, items ->
            val row = LinearLayout(context).apply { isBaselineAligned = false }
            items.forEachIndexed { column, item ->
                row.addView(button(item.first, "符号 ${item.first}，${item.second}", palette.key, 24) {
                    action("panel-select", (line * columns + column).toString())
                }, LayoutParams(0, dp(rowHeight), 1f))
            }
            repeat(columns - items.size) { row.addView(android.view.View(context), LayoutParams(0, dp(rowHeight), 1f)) }
            grid.addView(row)
        }
        addView(ScrollView(context).apply { addView(grid) }, LayoutParams(-1, dp(areaHeight - rowHeight - 36)))
        val bottom = LinearLayout(context).apply { isBaselineAligned = false }
        for ((label, key) in listOf("ABC" to "panel-close", "123" to "numbers", "空格" to "panel-space", "⌫" to "panel-delete", "换行" to "enter")) {
            bottom.addView(button(label, "按键 ${if (key == "enter") "回车" else label}", if (key == "enter") palette.accent else palette.function, 16) {
                action(key, "")
            }, LayoutParams(0, dp(rowHeight), if (key == "panel-space") 2.4f else 1f))
        }
        addView(bottom)
    }
}
