// 编辑器删除按 Unicode 字素边界处理，避免把家庭、肤色或旗帜表情拆开。
package dev.qingstudy.ime

import android.icu.text.BreakIterator

object EditorDeletion {
    fun lastGraphemeLength(before: String): Int {
        if (before.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance().apply { setText(before) }
        return before.length - iterator.preceding(before.length).coerceAtLeast(0)
    }
}
