//! 固定候选和完整第一排键帽；真实尺寸随设置变化，列表位置由宿主页补偿。
package dev.qingstudy.ime

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class AppearancePreview(context: Context, private val feedback: KeyboardFeedback) : FrameLayout(context) {
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    init { contentDescription = "固定键盘预览" }
    fun update(appearance: KeyboardAppearance) {
        removeAllViews()
        val palette = KeyboardPalette(context, appearance)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
        val candidates = LinearLayout(context)
        listOf("你好" to "hello; hi", "英语" to "English", "学习" to "learn; study").forEachIndexed { index, (word, gloss) ->
            candidates.addView(CandidateView(context, word, gloss, appearance = appearance, first = index == 0).apply {
                setOnClickListener { }
            }, LinearLayout.LayoutParams(0, dp(appearance.candidateHeight(context)), 1f))
        }
        content.addView(candidates)
        val row = LinearLayout(context).apply { isBaselineAligned = false }
        for (letter in "qwertyuiop") row.addView(KeyboardKey(context).apply {
            text = letter.uppercase(); contentDescription = "预览按键 ${letter.uppercase()}"; gravity = Gravity.CENTER
            isSingleLine = true; includeFontPadding = false; isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; stateListAnimator = null
            setAutoSizeTextTypeUniformWithConfiguration(10, appearance.keySize, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(palette.ink); background = palette.surface(palette.key, true)
            setPadding(0, if (appearance.auxiliary && !landscape) dp(12) else 0, 0, dp(2))
            if (appearance.auxiliary && !landscape) { secondary = KeySymbols.primary(letter.toString()).orEmpty(); secondaryColor = palette.muted }
            setOnClickListener { }
        }, LinearLayout.LayoutParams(0, dp(appearance.resolvedRowHeight(resources.configuration.screenWidthDp, landscape)), 1f))
        content.addView(row)
        content.addView(TextView(context).apply {
            text = if (appearance.bottomSpace >= 18) "额外底部留白 ${appearance.bottomSpace} dp" else ""
            contentDescription = "预览底部留白 ${appearance.bottomSpace} dp"
            setTextColor(palette.muted); textSize = 10f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(appearance.bottomSpace)))
        addView(content, LayoutParams(-1, -2))
        feedback.bind(content)
    }
}
