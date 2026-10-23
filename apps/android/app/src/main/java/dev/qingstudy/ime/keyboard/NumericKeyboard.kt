//! 参考已展示的数字面板：66/75/75/75/66 五列、侧栏符号、两行回车。
package dev.qingstudy.ime

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal class NumericKeyboard(
    context: Context,
    height: Int,
    key: (String, String, Int) -> View
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
        // 列共同使用同一组像素边界，避免每格 dp 取整造成底边偏移。
        val edges = (0..4).map { height * it / 4 }
        val columns = (0..4).map { index ->
            LinearLayout(context).apply {
                orientation = VERTICAL
                isBaselineAligned = false
                this@NumericKeyboard.addView(this, LayoutParams(0, height, if (index == 0 || index == 4) 66f else 75f))
            }
        }
        fun add(column: Int, row: Int, label: String, action: String, span: Int = 1) {
            val cellHeight = edges[row + span] - edges[row]
            columns[column].addView(key(label, action, cellHeight), LayoutParams(-1, cellHeight))
        }
        val symbols = LinearLayout(context).apply { orientation = VERTICAL }
        // 参考侧栏每屏约四个符号，上下滚动访问余项。
        val symbolHeight = edges[3] / 4
        for (symbol in listOf("%", "+", "-", "*", "/", "=", "#", ":", ".", "×", "@")) {
            symbols.addView(key(symbol, "literal", symbolHeight).apply {
                contentDescription = "数字符号 $symbol"
            }, LayoutParams(-1, symbolHeight))
        }
        columns[0].addView(ScrollView(context).apply {
            contentDescription = "数字侧栏符号"
            isVerticalScrollBarEnabled = false
            addView(symbols)
        }, LayoutParams(-1, edges[3]))
        add(0, 3, "返回", "layout")
        for (number in 1..9) add(1 + (number - 1) % 3, (number - 1) / 3, number.toString(), "literal")
        add(1, 3, "符号", "symbols")
        add(2, 3, "0", "literal")
        add(3, 3, ".", "literal")
        add(4, 0, "⌫", "delete")
        add(4, 1, "空格", "space")
        add(4, 2, "回车", "enter", 2)
    }
}
