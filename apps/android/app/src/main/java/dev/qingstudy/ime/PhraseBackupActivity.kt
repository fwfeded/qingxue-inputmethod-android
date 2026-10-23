// 短语备份的文件选择、后台校验和替换确认。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class PhraseBackupActivity : Activity() {
    private lateinit var state: PhraseBackupState
    private lateinit var export: Button
    private lateinit var restore: Button
    private lateinit var status: TextView
    private var confirmation: AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = lastNonConfigurationInstance as? PhraseBackupState ?: PhraseBackupState()
        val ui = SettingsUi(this)
        val box = ui.box
        box.addView(Button(this).apply { text = "返回短语设置"; setOnClickListener { finish() } })
        box.addView(TextView(this).apply {
            text = "备份包含全部短语及启停状态。导入前会预览，确认后替换全部现有短语，不合并。其他设置、词库和学习记录不受影响。文件可能含私人文本，请选择合适的保存位置。"; textSize = 16f
        })
        export = Button(this).apply {
            text = "导出短语备份"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "qingstudy-phrases-${System.currentTimeMillis()}.json"), 1)
            }
        }
        restore = Button(this).apply {
            text = "导入短语备份"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain")), 2)
            }
        }
        box.addView(export); box.addView(restore)
        status = TextView(this).apply { textSize = 16f; contentDescription = "短语备份结果" }
        box.addView(status)
        ui.styleContents()
        ui.show("短语导入与导出") { finish() }
        state.changed = { render() }; render()
    }
    override fun onRetainNonConfigurationInstance(): Any = state
    override fun onDestroy() {
        state.changed = null
        confirmation?.dismiss()
        if (isFinishing) { state.abandoned = true; state.preview = null }
        super.onDestroy()
    }
    private fun render() {
        export.isEnabled = !state.busy && state.preview == null
        restore.isEnabled = export.isEnabled
        status.text = state.message
        val preview = state.preview
        if (!state.busy && preview != null && confirmation == null && !isFinishing) {
            val rows = preview.getJSONArray("phrases")
            val previews = preview.getJSONArray("previews")
            val disabled = (0 until rows.length()).count { !rows.getJSONObject(it).getBoolean("enabled") }
            val sample = (0 until minOf(rows.length(), 3)).joinToString("\n") {
                val row = rows.getJSONObject(it)
                "${row.getString("code")} · 第${row.getInt("position")}位 · ${previews.getString(it)}"
            }
            confirmation = AlertDialog.Builder(this).setTitle("确认替换短语")
                .setMessage("已校验 ${rows.length()} 条（停用 $disabled 条）。\n$sample\n\n将替换全部现有短语，包括清空为空备份。不会合并；请先导出当前短语。确认后重新进入输入框生效。")
                .setNegativeButton("取消") { _, _ -> cancelPreview() }
                .setOnCancelListener { cancelPreview() }
                .setPositiveButton("替换全部短语") { _, _ ->
                    val result = runCatching { PhraseBackupFiles.replace(this, state.expected, preview) }
                    state.preview = null; confirmation = null
                    state.message = result.fold({ "已导入 ${rows.length()} 条短语；重新进入输入框生效。" }, { "操作失败：${it.message}" })
                    render()
                }.create()
            confirmation!!.show()
        }
    }
    private fun cancelPreview() {
        state.preview = null; confirmation = null
        state.message = "已取消导入，现有短语未改变。"; render()
    }
    private fun runOperation(message: String, work: (PhraseBackupState) -> Unit) {
        val operation = state
        operation.busy = true; operation.message = message; render()
        DictionaryFiles.executor.execute {
            val failure = runCatching { work(operation) }.exceptionOrNull()
            Handler(Looper.getMainLooper()).post {
                operation.busy = false
                if (failure != null) operation.message = "操作失败：${failure.message}"
                if (operation.abandoned) operation.preview = null
                operation.changed?.invoke()
            }
        }
    }
    @Deprecated("系统文档选择器回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 1..2 || state.busy) return
        if (resultCode != RESULT_OK) { state.message = "已取消文件选择，现有短语未改变。"; render(); return }
        val uri = data?.data ?: return
        val app = applicationContext
        val source = PhraseBackupFiles.current(app)
        if (requestCode == 1) runOperation("正在导出…") { operation ->
            val count = PhraseBackupFiles.export(app, uri, source)
            operation.message = "已导出 $count 条短语。"
        } else {
            state.expected = source
            runOperation("正在校验短语备份…") { operation ->
                operation.preview = PhraseBackupFiles.inspect(app, uri)
                operation.message = "校验通过，等待确认。"
            }
        }
    }
}
