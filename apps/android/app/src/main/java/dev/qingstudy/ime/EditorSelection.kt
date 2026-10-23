//! 清空组合前核对宿主当前选区，避免迟到的语音结束通知清掉后续拼音。
package dev.qingstudy.ime

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal object EditorSelection {
    // 只在通知将导致组合/续词被清除时调用；普通逐键输入不增加同步查询。
    // 不记录或保留宿主文本；不支持快照的编辑器维持原选区处理。
    fun isCurrent(connection: InputConnection?, start: Int, end: Int): Boolean {
        if (connection == null) return true
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = runCatching { connection.getSurroundingText(0, 0, 0) }.getOrNull()
            if (surrounding != null && surrounding.offset >= 0) {
                return same(surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd, start, end)
            }
        }
        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1; hintMaxLines = 1 }, 0)
        }.getOrNull() ?: return true
        if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return true
        return same(extracted.startOffset, extracted.selectionStart, extracted.selectionEnd, start, end)
    }

    private fun same(offset: Int, start: Int, end: Int, expectedStart: Int, expectedEnd: Int): Boolean =
        offset.toLong() + start == expectedStart.toLong() && offset.toLong() + end == expectedEnd.toLong()
}
