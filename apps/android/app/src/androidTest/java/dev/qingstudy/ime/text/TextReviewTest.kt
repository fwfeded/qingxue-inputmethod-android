//! 核对状态和核心原文保护的产品边界，不允许过期/不完整输出被采用。
package dev.qingstudy.ime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TextReviewTest {
    @Test fun adoptionUndoEditsAndRotationKeepTheOriginal() {
        val source = "这笔钱不是八千是一万八千元不要今天转账等合同签完再转"
        val edited = "这笔钱不是八千，是一万八千元。不要今天转账，等合同签完再转。"
        val draft = TextReviewDraft(source)
        assertFalse(draft.adopt())
        assertTrue(draft.offer(source, edited, true)); assertEquals(source, draft.source)
        assertTrue(draft.adopt()); assertEquals(edited, draft.source)
        val saved = android.os.Bundle().also(draft::save)
        val restored = TextReviewDraft().also { it.restore(saved) }
        assertTrue(restored.revert()); assertEquals(source, restored.source)
        draft.edit("新的原文")
        assertFalse(draft.offer(source, edited, true)); assertFalse(draft.adopt()); assertFalse(draft.revert())
        assertTrue(draft.offer("新的原文", "被截断", false)); assertFalse(draft.adopt())
    }
    @Test fun nativePolicyRejectsChangedFactsAndTruncation() {
        fun accepted(source: String, output: String, complete: Boolean = true) = JSONObject(NativeBridge.textTool(source, output, complete)).getBoolean("accepted")
        assertTrue(accepted("明天不要转账", "明天不要转账。"))
        assertFalse(accepted("明天不要转账", "明天转账。"))
        assertFalse(accepted("文件a-b.txt", "文件abtxt。"))
        assertFalse(accepted("金额1.5元", "金额15元。"))
        assertFalse(accepted("明天开会", "明天开会。", false))
        assertTrue(runCatching { NativeBridge.textTool("字".repeat(601), "", false) }.isFailure)
    }
}
