//! R4长按备选：参考26键大小写顺序，保留既有副符号；提交交给Rust。
package dev.qingstudy.ime

internal object KeySymbols {
    private val letters = "qwertyuiopasdfghjklzxcvbnm"
    private val values = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "-", "/", ":", ";", "(", ")", "~", "\"", "'", "@", ".", "#", "\\", "?", "!", "…")
    fun primary(letter: String): String? {
        val index = letters.indexOf(letter.singleOrNull() ?: return null)
        return index.takeIf { it >= 0 }?.let(values::get)
    }
    fun choices(letter: String, includeSymbols: Boolean = true): List<String> {
        val first = primary(letter) ?: return emptyList()
        val upper = letter.uppercase()
        val cases = if (letter in listOf("p", "l")) listOf(letter, upper) else listOf(upper, letter)
        if (!includeSymbols) return cases
        val basic = when (letter) {
            "q", "a" -> listOf(first, upper, letter)
            "p", "l" -> cases + first
            else -> listOf(upper, first, letter)
        }
        val extra = when (letter) {
            "e" -> listOf("€", "£", "¥")
            "f" -> listOf(";", ":")
            "g" -> listOf("[", "{")
            "h" -> listOf("]", "}")
            "l" -> listOf("'", "\"", "`")
            "x" -> listOf(",", ".", "…")
            "v" -> listOf("/", "|")
            "b" -> listOf("!", "?")
            else -> emptyList()
        }
        return (basic + extra).distinct()
    }
}
