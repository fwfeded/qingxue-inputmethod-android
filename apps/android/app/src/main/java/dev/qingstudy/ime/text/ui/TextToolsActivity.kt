//! 设置中的手动文字整理入口；旋转保留原文和撤回记录，后台取消推理。
package dev.qingstudy.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class TextToolsActivity : Activity() {
    private val draft = TextReviewDraft()
    private lateinit var workspace: TextWorkspace
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) draft.restore(savedInstanceState) else draft.source = intent.getStringExtra("source").orEmpty()
        val ui = SettingsUi(this)
        workspace = TextWorkspace(this, draft, models = { startActivity(Intent(this, TextModelActivity::class.java)) })
        ui.box.addView(workspace)
        ui.show("智能文字整理") { finish() }
        ui.box.isFocusableInTouchMode = true; ui.box.requestFocus()
    }
    override fun onSaveInstanceState(outState: Bundle) { draft.save(outState); super.onSaveInstanceState(outState) }
    override fun onStop() { workspace.close(); super.onStop() }
    override fun onDestroy() { workspace.close(); super.onDestroy() }
}
