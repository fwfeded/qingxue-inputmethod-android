//! 人工核对状态：编辑使结果失效，采用前核对原文快照，撤回恢复完整草稿。
package dev.qingstudy.ime

internal class TextReviewDraft(var source: String = "") {
    var result = ""; private set
    var snapshot = ""; private set
    var accepted = false; private set
    var undo: String? = null; private set
    fun edit(value: String) { if (source != value) { source = value; clear(); undo = null } }
    fun clear() { result = ""; accepted = false; snapshot = "" }
    fun offer(original: String, output: String, allowed: Boolean): Boolean {
        if (original != source) return false
        snapshot = original; result = output; accepted = allowed && output.isNotBlank(); return true
    }
    fun adopt(): Boolean {
        if (!accepted || snapshot != source) return false
        undo = source; source = result; clear(); return true
    }
    fun revert(): Boolean { val previous = undo ?: return false; source = previous; undo = null; clear(); return true }
    fun save(out: android.os.Bundle) {
        out.putString("text_source", source); out.putString("text_undo", undo)
        out.putString("text_result", result); out.putString("text_snapshot", snapshot); out.putBoolean("text_allowed", accepted)
    }
    fun restore(saved: android.os.Bundle) {
        source = saved.getString("text_source").orEmpty(); undo = saved.getString("text_undo")
        result = saved.getString("text_result").orEmpty(); snapshot = saved.getString("text_snapshot").orEmpty(); accepted = saved.getBoolean("text_allowed")
    }
}
