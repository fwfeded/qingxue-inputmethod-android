//! 已确认的六套主题令牌；Catppuccin / Nord 的来源及 MIT 许可随 APK 分发。
package dev.qingstudy.ime

internal object KeyboardThemes {
    val names = linkedMapOf("qing" to "青学 · 雾白", "latte" to "Latte · 奶霜", "frappe" to "Frappé · 烟蓝",
        "macchiato" to "Macchiato · 暮色", "mocha" to "Mocha · 夜紫", "nord" to "Nord · 极地")
    // 顺序：背景、键帽、功能键、高亮底、正文、次要文字、高亮前景、按下态。
    private val tokens = mapOf(
        "qing" to "E5E9E7 FFFFFF CFD8D3 D2E6D7 26362F 52665A 216A47 B7CEC0",
        "latte" to "DCE0E8 EFF1F5 CCD0DA 8839EF 4C4F69 5C5F77 EFF1F5 BCC0CC",
        "frappe" to "232634 303446 414559 CA9EE6 C6D0F5 B5BFE2 232634 51576D",
        "macchiato" to "181926 24273A 363A4F C6A0F6 CAD3F5 B8C0E0 181926 494D64",
        "mocha" to "11111B 1E1E2E 313244 CBA6F7 CDD6F4 BAC2DE 11111B 45475A",
        "nord" to "2E3440 3B4252 434C5E 88C0D0 ECEFF4 D8DEE9 2E3440 4C566A",
        "dark" to "202B26 34433A 435447 446E56 E4ECE5 ACBAB0 E4ECE5 51705E"
    )
    fun valid(id: String) = id == "light" || tokens.containsKey(id)
    fun colors(id: String) = tokens.getValue(if (id == "light") "qing" else id).split(' ').map { android.graphics.Color.parseColor("#$it") }
    fun name(id: String) = names[id] ?: if (id == "dark") "墨绿夜间（原设置）" else names.getValue("qing")
}
