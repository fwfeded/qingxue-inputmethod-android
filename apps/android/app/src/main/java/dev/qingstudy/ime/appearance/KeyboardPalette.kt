// 键盘共享颜色与按下反馈，每个控件使用独立 Drawable。
package dev.qingstudy.ime

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable

class KeyboardPalette(context: Context, private val appearance: KeyboardAppearance) {
    val themeId = if (appearance.theme == "system") {
        if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            appearance.nightTheme else appearance.dayTheme
    } else appearance.theme
    val dark = themeId !in listOf("qing", "light", "latte")
    private val colors = KeyboardThemes.colors(themeId)
    val background = colors[0]
    val key = colors[1]
    val function = colors[2]
    val accent = colors[3]
    val ink = colors[4]
    val muted = colors[5]
    val onAccent = colors[6]
    val pressed = colors[7]
    val highlight = when (themeId) {
        "qing", "light" -> onAccent
        "latte" -> ink // 紫色高亮只用于底色；浅灰上的小字使用正文色保证对比度。
        "dark" -> 0xFFB3E1BD.toInt()
        else -> accent
    }
    private val density = context.resources.displayMetrics.density
    fun surface(color: Int, inset: Boolean = false): android.graphics.drawable.Drawable {
        val fill = if (inset && !appearance.keyBorders && color == key) background else color
        val shape = GradientDrawable().apply { setColor(fill); cornerRadius = (if (inset) appearance.keyRadius else 6) * density }
        val ripple = RippleDrawable(ColorStateList.valueOf((pressed and 0xFFFFFF) or 0x99000000.toInt()), shape, null)
        return if (inset) InsetDrawable(ripple, (appearance.keyGap * density / 2).toInt()) else ripple
    }
}
