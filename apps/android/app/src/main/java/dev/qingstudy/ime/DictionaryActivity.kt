// 词库管理：系统文件选择器授权多选读取或单个保存，不索取存储目录权限。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class DictionaryActivity : Activity() {
    private lateinit var state: DictionaryState
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private var exportId = ""
    private var exportFormat = "qj"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = lastNonConfigurationInstance as? DictionaryState ?: DictionaryState().apply {
            message = savedInstanceState?.getString("message") ?: ""
        }
        exportId = savedInstanceState?.getString("exportId") ?: ""
        exportFormat = savedInstanceState?.getString("exportFormat") ?: "qj"
        val ui = SettingsUi(this)
        val box = ui.box
        box.addView(Button(this).apply { text = "返回设置"; setOnClickListener { finish() } })
        box.addView(TextView(this).apply {
            text = "支持 TSV / Rime / QJ，可多选。重新打开输入框后生效。"
            textSize = 16f
        })
        box.addView(Button(this).apply {
            text = "格式说明与使用限制"
            setOnClickListener {
                AlertDialog.Builder(this@DictionaryActivity).setTitle("词库文件说明")
                    .setMessage("可长按文件后多选，一次最多 32 个。逐个导入，失败不影响其他文件，结果列出每个文件。\n\nTSV 使用 UTF-8（可带 BOM），每行依次为词、空格分隔的拼音、词频，以制表符分列。Rime 使用标准列顺序，不支持 import_tables 或 columns。\n\n每次导入新增一本，不覆盖旧词库；移除后仍在本机保留备份。导出只包含所选词库，不含输入记录或设置。本应用导出的 QJ 和 TSV 均保留名称、来源和许可；也兼容 0.3.0 的 TSV 备份。请保留 TSV 首行备份信息。\n\n单本最大 16 MB、25 万条，合计最多 32 本、64 MB。")
                    .setPositiveButton("知道了", null).show()
            }
        })
        importButton = Button(this).apply {
            text = "导入词库文件"; setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1)
            }
        }
        box.addView(importButton)
        status = TextView(this).apply { textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        box.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        ui.styleContents()
        ui.show("词库导入与导出") { finish() }
        state.changed = { renderState() }
        renderState()
    }

    override fun onRetainNonConfigurationInstance(): Any = state
    override fun onDestroy() {
        state.changed = null
        super.onDestroy()
    }
    private fun renderState() {
        importButton.isEnabled = !state.busy
        status.text = state.message
        if (state.busy) list.removeAllViews() else render(state.rows)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("message", if (state.busy) "上次操作可能未完成，请核对词库列表；不会自动重复导入。" else state.message)
        outState.putString("exportId", exportId); outState.putString("exportFormat", exportFormat)
        super.onSaveInstanceState(outState)
    }
    private fun operation(work: () -> String) {
        if (state.busy) return
        val operation = state
        val app = applicationContext
        operation.busy = true
        operation.message = "正在处理…"
        renderState()
        DictionaryFiles.executor.execute {
            val result = runCatching(work)
            val rows = runCatching { JSONArray(DictionaryFiles.manage(app, "list")) }
            Handler(Looper.getMainLooper()).post {
                operation.message = result.getOrElse { "操作失败：${it.message}" }
                rows.onSuccess { operation.rows = it }.onFailure { operation.message += "\n读取列表失败：${it.message}" }
                operation.busy = false
                operation.changed?.invoke()
            }
        }
    }
    private fun refresh() {
        if (state.busy) return
        val operation = state
        val app = applicationContext
        DictionaryFiles.executor.execute {
            val result = runCatching { JSONArray(DictionaryFiles.manage(app, "list")) }
            Handler(Looper.getMainLooper()).post {
                if (!operation.busy) {
                    result.onSuccess { operation.rows = it }.onFailure { operation.message = "读取失败：${it.message}" }
                    operation.changed?.invoke()
                }
            }
        }
    }
    private fun render(rows: JSONArray) {
        list.removeAllViews()
        if (rows.length() == 0) list.addView(TextView(this).apply { text = "暂无导入词库" })
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            val name = row.optString("name", "用户词库")
            val enabled = row.getBoolean("enabled")
            list.addView(TextView(this).apply {
                text = "$name · ${row.getInt("entries")} 条 · ${if (enabled) "已启用" else "已停用"}\n许可：${row.optString("license").ifBlank { "源文件未声明" }}"
                textSize = 18f; setPadding(0, 24, 0, 4)
            })
            var buttonRow: LinearLayout? = null
            var buttonCount = 0
            fun button(title: String, action: () -> Unit) {
                if (buttonCount++ % 2 == 0) {
                    buttonRow = LinearLayout(this)
                    list.addView(buttonRow)
                }
                buttonRow!!.addView(Button(this).apply { text = title; contentDescription = "$title $name"; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            button(if (enabled) "停用" else "启用") {
                operation { DictionaryFiles.manage(applicationContext, "toggle", JSONObject().put("id", id).put("enabled", !enabled)); "已${if (enabled) "停用" else "启用"}：$name" }
            }
            button("导出 QJ") { chooseExport(id, "qj", name) }
            button("导出 TSV") { chooseExport(id, "tsv", name) }
            button("移除") {
                AlertDialog.Builder(this).setTitle("移除 $name？").setMessage("停止使用并保留本机备份。建议先导出，以便以后重新导入。")
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        operation { DictionaryFiles.manage(applicationContext, "remove", JSONObject().put("id", id)); "已移除：$name" }
                    }.show()
            }
        }
    }
    private fun chooseExport(id: String, format: String, name: String) {
        exportId = id; exportFormat = format
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (format == "tsv") "text/tab-separated-values" else "application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "${name.replace(Regex("[/\\\\:]"), "_")}.$format"), 2)
    }
    @Deprecated("系统文档选择器回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 1..2 || state.busy) return
        if (resultCode != RESULT_OK) {
            state.message = "已取消文件选择，词库未改变。"; renderState(); return
        }
        val app = applicationContext
        if (requestCode == 1) {
            val sources = buildList {
                data?.clipData?.let { clips ->
                    if (clips.itemCount > 32) {
                        state.message = "一次最多选择 32 个词库文件，本次未导入。"; renderState(); return
                    }
                    for (i in 0 until clips.itemCount) clips.getItemAt(i).uri?.let { add(it) }
                }
                data?.data?.let { add(it) }
            }
            operation { DictionaryFiles.importSelected(app, sources) }
        } else {
            val uri = data?.data ?: return
            val id = exportId; val format = exportFormat
            operation { DictionaryFiles.export(app, id, format, uri); "已导出 $format，可在其他设备重新导入。" }
        }
    }
}
