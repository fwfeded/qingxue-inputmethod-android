// 键盘显示配置；与输入引擎配置分离，跨进程仍通过原子设置文件读取。
package dev.qingstudy.ime

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import org.json.JSONObject
import kotlin.math.ceil

data class KeyboardAppearance(
    val rowHeight: Int = 52,
    val landscapeHeight: Int = 34,
    val keySize: Int = 24,
    val candidateSize: Int = 20,
    val glossSize: Int = 13,
    val theme: String = "system",
    val autoHeight: Boolean = true,
    val keyGap: Int = 5,
    val keyRadius: Int = 8,
    val bottomSpace: Int = 0,
    val splitBottom: Boolean = true,
    val auxiliary: Boolean = true,
    val swipeSymbols: Boolean = true,
    val longPressSymbols: Boolean = true,
    val numberRow: Boolean = false,
    val keyBorders: Boolean = true,
    val dayTheme: String = "qing",
    val nightTheme: String = "mocha"
) {
    fun resolvedRowHeight(widthDp: Int, landscape: Boolean): Int = when {
        landscape -> landscapeHeight
        autoHeight -> kotlin.math.round(widthDp * .145f).toInt().coerceIn(48, 62)
        else -> rowHeight
    }
    fun textPixels(context: Context, sp: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics)

    fun candidateHeight(context: Context): Int {
        val paint = Paint()
        fun height(size: Int): Float {
            paint.textSize = textPixels(context, size)
            return paint.fontMetrics.run { bottom - top }
        }
        val density = context.resources.displayMetrics.density
        return maxOf(52, ceil((height(candidateSize) + height(glossSize)) / density + 7).toInt())
    }

    fun toJson() = JSONObject().put("rowHeight", rowHeight).put("landscapeHeight", landscapeHeight)
        .put("keySize", keySize).put("candidateSize", candidateSize).put("glossSize", glossSize).put("theme", theme).put("autoHeight", autoHeight)
        .put("keyGap", keyGap).put("keyRadius", keyRadius).put("bottomSpace", bottomSpace).put("splitBottom", splitBottom)
        .put("auxiliary", auxiliary).put("swipeSymbols", swipeSymbols).put("longPressSymbols", longPressSymbols).put("numberRow", numberRow)
        .put("keyBorders", keyBorders).put("dayTheme", dayTheme).put("nightTheme", nightTheme)

    companion object {
        fun read(context: Context): KeyboardAppearance = fromJson(Preferences.read(context).optJSONObject("appearance") ?: JSONObject())
        fun fromJson(json: JSONObject) = KeyboardAppearance(
            json.optInt("rowHeight", 52).coerceIn(44, 66),
            json.optInt("landscapeHeight", 34).coerceIn(30, 42),
            json.optInt("keySize", 24).coerceIn(18, 30),
            json.optInt("candidateSize", 20).coerceIn(18, 30),
            json.optInt("glossSize", 13).coerceIn(11, 18),
            json.optString("theme", "system").takeIf { it == "system" || KeyboardThemes.valid(it) } ?: "system",
            // 升级时保留用户在 R2 明确保存过的行高；新安装默认自适应。
            json.optBoolean("autoHeight", !json.has("rowHeight")),
            json.optInt("keyGap", 5).coerceIn(3, 7),
            json.optInt("keyRadius", 8).coerceIn(4, 12),
            json.optInt("bottomSpace", 0).coerceIn(0, 36),
            json.optBoolean("splitBottom", true), json.optBoolean("auxiliary", true),
            json.optBoolean("swipeSymbols", true), json.optBoolean("longPressSymbols", true), json.optBoolean("numberRow", false),
            json.optBoolean("keyBorders", true),
            json.optString("dayTheme", "qing").takeIf { KeyboardThemes.valid(it) } ?: "qing",
            // 旧跟随系统配置继续使用原墨绿；新安装使用已确认的夜紫。
            json.optString("nightTheme", if (json.has("theme")) "dark" else "mocha").takeIf { KeyboardThemes.valid(it) } ?: "mocha"
        )
        fun save(context: Context, value: KeyboardAppearance) {
            Preferences.save(context, Preferences.read(context).put("appearance", value.toJson()))
        }
    }
}
