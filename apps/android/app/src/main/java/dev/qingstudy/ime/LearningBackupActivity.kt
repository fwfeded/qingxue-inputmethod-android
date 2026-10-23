// 学习备份的文档选择、预览确认与后台恢复；旋转时保留操作状态。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LearningBackupActivity : Activity() {
    private lateinit var export: Button
    private lateinit var status: TextView
    private lateinit var restore: Button
    private lateinit var state: LearningBackupState
    private var confirmation: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = lastNonConfigurationInstance as? LearningBackupState ?: LearningBackupState()
        val ui = SettingsUi(this)
        val box = ui.box
        box.addView(Button(this).apply { text = "返回设置"; setOnClickListener { finish() } })
        box.addView(TextView(this).apply {
            text = getString(R.string.learning_backup_summary)
            textSize = 16f
        })
        box.addView(Button(this).apply {
            text = "备份范围与使用说明"
            setOnClickListener {
                AlertDialog.Builder(this@LearningBackupActivity).setTitle("学习备份说明")
                    .setMessage(R.string.learning_backup_details).setPositiveButton("知道了", null).show()
            }
        })
        export = Button(this).apply {
            text = "导出学习备份"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "qingstudy-learning-${System.currentTimeMillis()}.json"), 1)
            }
        }
        box.addView(export)
        restore = Button(this).apply {
            text = "恢复学习备份"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain")), 2)
            }
        }
        box.addView(restore)
        status = TextView(this).apply {
            textSize = 16f; contentDescription = "学习备份结果"
            text = savedInstanceState?.getString("status") ?: ""
        }
        box.addView(status)
        ui.styleContents()
        ui.show("学习记录备份") { finish() }
        state.changed = { render() }
        render()
    }

    override fun onRetainNonConfigurationInstance(): Any = state

    override fun onDestroy() {
        state.changed = null
        confirmation?.dismiss()
        if (isFinishing) state.abandoned = true
        if (isFinishing && !state.busy) { state.preview?.delete(); state.preview = null }
        super.onDestroy()
    }

    private fun render() {
        export.isEnabled = !state.busy && state.preview == null
        restore.isEnabled = export.isEnabled
        status.text = state.message
        if (!state.busy && state.preview != null && confirmation == null && !isFinishing) {
            confirmation = AlertDialog.Builder(this).setTitle("确认恢复学习记录")
                .setMessage("已校验 ${state.tables} 张表、${state.rows} 条记录。\n\n恢复将替换当前学习记录，不合并计数。原记录保留在本机。设置和附加词库不受影响。请先完成正在输入的内容，恢复后重新点选输入框。")
                .setPositiveButton("替换并恢复") { _, _ ->
                    val source = state.preview!!
                    state.preview = null
                    confirmation = null
                    runOperation("正在恢复…") { app, operation ->
                        try {
                            val result = LearningBackupFiles.restore(app, source)
                            operation.message = "已恢复学习备份：${result.getInt("rows")} 条记录。请重新点选输入框。"
                        } finally { source.delete() }
                    }
                }
                .setNegativeButton("取消") { _, _ -> cancelPreview() }
                .setOnCancelListener { cancelPreview() }.create()
            confirmation!!.show()
        }
    }

    private fun cancelPreview() {
        state.preview?.delete(); state.preview = null
        confirmation = null
        state.message = "已取消恢复，当前记录未改变。"
        render()
    }

    private fun runOperation(message: String, work: (android.content.Context, LearningBackupState) -> Unit) {
        val operation = state
        val app = applicationContext
        operation.busy = true; operation.message = message
        render()
        DictionaryFiles.executor.execute {
            val failure = runCatching { work(app, operation) }.exceptionOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                operation.busy = false
                if (failure != null) operation.message = "操作失败：${failure.message}"
                operation.changed?.invoke()
                if (operation.abandoned) { operation.preview?.delete(); operation.preview = null }
            }
        }
    }

    @Deprecated("系统文档选择器回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 1..2 || resultCode != RESULT_OK || state.busy) return
        val uri = data?.data ?: return
        if (requestCode == 1) runOperation("正在导出…") { app, operation ->
            val result = LearningBackupFiles.export(app, uri)
            operation.message = app.getString(R.string.learning_backup_success, result.getInt("files"))
        } else runOperation("正在校验备份…") { app, operation ->
            val file = LearningBackupFiles.stage(app, uri)
            try {
                val result = LearningBackupFiles.inspect(app, file)
                operation.rows = result.getInt("rows"); operation.tables = result.getInt("files")
                operation.preview = file
                operation.message = "校验通过，等待确认恢复。"
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }
}
