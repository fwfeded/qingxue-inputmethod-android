//! 语音编辑中的核对浮层，不切换宿主输入框；关闭后仍可用语音撤回恢复原文。
package dev.qingstudy.ime

import android.app.AlertDialog
import android.inputmethodservice.InputMethodService
import android.view.WindowManager
import android.widget.ScrollView

internal class TextVoiceReview(private val ime: InputMethodService, source: String, models: () -> Unit, changed: (String) -> Unit) : AutoCloseable {
    private val workspace = TextWorkspace(ime, TextReviewDraft(source), editable = false, models = { close(); models() }, changed = changed)
    private val dialog = AlertDialog.Builder(ime).setTitle("智能文字整理")
        .setView(ScrollView(ime).apply { addView(workspace) }).setNegativeButton("返回语音编辑", null).create()
    init {
        dialog.window?.apply {
            attributes = attributes.apply { token = ime.window.window?.decorView?.windowToken; type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG }
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.setOnDismissListener { workspace.close() }
        dialog.show()
        dialog.window?.setLayout(-1, (ime.resources.displayMetrics.heightPixels * .78).toInt())
    }
    override fun close() { workspace.close(); dialog.dismiss() }
}
