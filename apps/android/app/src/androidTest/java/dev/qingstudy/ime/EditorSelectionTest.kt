//! 选区快照的绝对偏移、旧接口和不可用接口边界。
package dev.qingstudy.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import org.junit.Assert.*
import org.junit.Test

class EditorSelectionTest {
    private class Connection(
        val surrounding: SurroundingText? = null,
        val extracted: ExtractedText? = null,
        val broken: Boolean = false
    ) : InputConnectionWrapper(null, false) {
        var extractions = 0
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            assertEquals(0, beforeLength); assertEquals(0, afterLength); assertEquals(0, flags)
            if (broken) throw IllegalStateException("disconnected")
            return surrounding
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            extractions++
            assertEquals(0, flags)
            if (broken) throw IllegalStateException("disconnected")
            return extracted
        }
    }
    @Test fun staleVoiceEndCannotMatchNewPinyinCursor() {
        val connection = Connection(SurroundingText("", 0, 0, 8))
        assertFalse(EditorSelection.isCurrent(connection, 3, 3))
        assertTrue(EditorSelection.isCurrent(connection, 8, 8))
        assertEquals(0, connection.extractions)
    }
    @Test fun genuineAndReversedSelectionAreNotIgnored() {
        val connection = Connection(SurroundingText("abcd", 3, 1, 20))
        assertTrue(EditorSelection.isCurrent(connection, 23, 21))
        assertFalse(EditorSelection.isCurrent(connection, 21, 23))
    }
    @Test fun unknownOffsetFallsBackToLegacyAbsoluteCoordinates() {
        val connection = Connection(SurroundingText("", 0, 0, -1), ExtractedText().apply {
            startOffset = 10; selectionStart = 2; selectionEnd = 4; text = "abcdef"
        })
        assertTrue(EditorSelection.isCurrent(connection, 12, 14))
        assertFalse(EditorSelection.isCurrent(connection, 2, 4))
    }
    @Test fun unavailableOrBrokenSnapshotPreservesCursorHandling() {
        assertTrue(EditorSelection.isCurrent(Connection(), 0, 0))
        assertTrue(EditorSelection.isCurrent(Connection(broken = true), 0, 0))
        assertTrue(EditorSelection.isCurrent(null, 0, 0))
    }
}
