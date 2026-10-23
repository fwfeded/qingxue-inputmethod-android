// 短语编辑与持久化；校验、预览和候选插入交给共享 Rust 核心。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class PhraseActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var draft: (() -> Bundle)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = SettingsUi(this)
        val box = ui.box
        box.addView(Button(this).apply { text = "返回设置"; setOnClickListener { finish() } })
        box.addView(TextView(this).apply { text = "输入码敲全后按固定位置显示。长按候选查看完整文本；重新进入输入框生效。"; textSize = 16f })
        box.addView(Button(this).apply { text = "新增短语"; setOnClickListener { edit(-1, null, JSONObject().put("position", 1).put("enabled", true)) } })
        box.addView(Button(this).apply {
            text = "短语导入导出"
            setOnClickListener { startActivity(Intent(this@PhraseActivity, PhraseBackupActivity::class.java)) }
        })
        status = TextView(this).apply { textSize = 16f; contentDescription = "短语状态" }
        box.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        box.addView(Button(this).apply {
            text = "清空全部短语"
            setOnClickListener {
                AlertDialog.Builder(this@PhraseActivity).setTitle("清空全部短语？").setMessage("此操作删除自定义短语规则，不能撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                        Preferences.save(this@PhraseActivity, Preferences.read(this@PhraseActivity).put("customPhrases", JSONArray())); refresh()
                    }.show()
            }
        })
        ui.styleContents()
        ui.show("自定义短语") { finish() }
        refresh()
        savedInstanceState?.getBundle("editor")?.let {
            edit(it.getInt("index"), it.getString("expected"), JSONObject().put("code", it.getString("code"))
                .put("text", it.getString("text")).put("enabled", it.getBoolean("enabled")), it.getString("position"))
        }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onSaveInstanceState(outState: Bundle) {
        draft?.invoke()?.let { outState.putBundle("editor", it) }
        super.onSaveInstanceState(outState)
    }
    private fun change(index: Int, expected: String?, rule: JSONObject?) {
        val fresh = Preferences.read(this)
        val rows = fresh.optJSONArray("customPhrases") ?: JSONArray()
        check(expected == null || rows.toString() == expected) { "规则已变化，请返回后重新编辑" }
        val updated = JSONArray()
        for (i in 0 until rows.length()) {
            if (i != index) updated.put(rows.getJSONObject(i)) else if (rule != null) updated.put(rule)
        }
        if (index == -1 && rule != null) updated.put(rule)
        val validated = JSONObject(NativeBridge.phrases(updated.toString())).getJSONArray("phrases")
        Preferences.save(this, fresh.put("customPhrases", validated))
    }
    private fun refresh() {
        list.removeAllViews()
        val raw = Preferences.read(this).optJSONArray("customPhrases") ?: JSONArray()
        runCatching { JSONObject(NativeBridge.phrases(raw.toString())) }.onSuccess { result ->
            val rows = result.getJSONArray("phrases")
            val previews = result.getJSONArray("previews")
            status.text = getString(R.string.phrase_count, rows.length())
            for (i in 0 until rows.length()) {
                val rule = rows.getJSONObject(i)
                val identity = "${rule.getString("code")} 第${rule.getInt("position")}位"
                list.addView(Switch(this).apply {
                    text = getString(R.string.phrase_row, rule.getString("code"), rule.getInt("position"), previews.getString(i))
                    contentDescription = "启用短语 $identity"; textSize = 18f; minHeight = 120
                    isChecked = rule.getBoolean("enabled")
                    setOnCheckedChangeListener { _, enabled ->
                        val error = runCatching { change(i, raw.toString(), JSONObject(rule.toString()).put("enabled", enabled)) }.exceptionOrNull()
                        refresh()
                        if (error != null) status.text = getString(R.string.phrase_error, error.message)
                    }
                })
                val actions = LinearLayout(this)
                actions.addView(Button(this).apply {
                    text = "编辑"; contentDescription = "编辑短语 $identity"
                    setOnClickListener { edit(i, raw.toString(), rule) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(Button(this).apply {
                    text = "删除"; contentDescription = "删除短语 $identity"
                    setOnClickListener {
                        AlertDialog.Builder(this@PhraseActivity).setTitle("删除这条短语？").setMessage(previews.getString(i))
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                val error = runCatching { change(i, raw.toString(), null) }.exceptionOrNull()
                                refresh()
                                if (error != null) status.text = getString(R.string.phrase_error, error.message)
                            }.show()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(actions)
            }
        }.onFailure { status.text = getString(R.string.phrase_load_error, it.message) }
    }
    private fun edit(index: Int, expected: String?, rule: JSONObject, positionText: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun field(label: String, value: String, type: Int, single: Boolean): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                contentDescription = label; inputType = type; isSingleLine = single; setText(value)
                if (!single) { minLines = 3; maxLines = 5 }
                box.addView(this)
            }
        }
        val code = field("短语输入码", rule.optString("code"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true)
        code.privateImeOptions = "dev.qingstudy.ime.literal"
        val position = field("短语位置", positionText ?: rule.optInt("position", 1).toString(), InputType.TYPE_CLASS_NUMBER, true)
        val text = field("短语文本", rule.optString("text"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, false)
        val enabled = Switch(this).apply { this.text = "启用这条短语"; isChecked = rule.optBoolean("enabled", true) }
        box.addView(enabled)
        val error = TextView(this).apply { contentDescription = "短语校验错误"; textSize = 16f }
        box.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(if (index < 0) "新增短语" else "编辑短语")
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("取消", null).setPositiveButton("保存规则", null).create()
        draft = { Bundle().apply {
            putInt("index", index); putString("expected", expected); putString("code", code.text.toString())
            putString("position", position.text.toString()); putString("text", text.text.toString()); putBoolean("enabled", enabled.isChecked)
        } }
        dialog.setOnDismissListener { draft = null }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                change(index, expected, JSONObject().put("code", code.text.toString()).put("text", text.text.toString())
                    .put("position", position.text.toString().toIntOrNull() ?: 0).put("enabled", enabled.isChecked))
            }.onSuccess { dialog.dismiss(); refresh() }
                .onFailure { error.text = getString(R.string.phrase_error, it.message); error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height)) }
        }
    }
}
