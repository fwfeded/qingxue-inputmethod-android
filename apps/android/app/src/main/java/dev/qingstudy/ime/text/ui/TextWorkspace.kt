//! 已确认的原文/结果对照组件；手动生成、取消、采用和撤回，无自动上屏或学习。
package dev.qingstudy.ime

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

internal class TextWorkspace(context: Context, val draft: TextReviewDraft, editable: Boolean = true,
    private val models: () -> Unit, private val changed: (String) -> Unit = {}) : LinearLayout(context), AutoCloseable {
    private val palette = KeyboardPalette(context, KeyboardAppearance.read(context))
    private val editor = EditText(context)
    private val status = label("先整理，再逐句核对。不会自动发送或写入学习记录。", "文字整理状态")
    private val output = TextView(context)
    private lateinit var generate: Button
    private lateinit var cancel: Button
    private lateinit var adopt: Button
    private lateinit var keep: Button
    private lateinit var undo: Button
    private var busy = false
    private var setting = false
    private var generation = 0
    private var client: TextClient? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        label("原文", "原文标题")
        editor.apply {
            contentDescription = "整理原文"; hint = "输入或粘贴需要整理的文字（最多 600 字）"
            textSize = 18f; minLines = 3; maxLines = 7; gravity = Gravity.TOP
            setTextColor(palette.ink); setHintTextColor(palette.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = palette.surface(palette.function)
            setText(draft.source)
            if (!editable) { isFocusable = false; showSoftInputOnFocus = false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!setting) { stop(); draft.edit(s.toString()); status.text = "原文已更新，请重新整理"; render() }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(editor, LayoutParams(-1, -2))
        generate = button("开始整理") { run() }
        cancel = button("取消整理") { stop(); status.text = "已取消，原文保留"; render() }
        label("整理结果 · 请核对后采用", "整理结果标题")
        output.apply { textSize = 18f; setTextColor(palette.ink); setTextIsSelectable(true); contentDescription = "整理结果"; setPadding(dp(12), dp(12), dp(12), dp(12)); background = palette.surface(palette.function) }
        addView(output, LayoutParams(-1, -2))
        adopt = button("采用结果") {
            if (draft.adopt()) { updateEditor(); changed(draft.source); status.text = "已采用，可撤回到完整原文"; render() }
        }
        keep = button("保留原文") { draft.clear(); status.text = "原文保留"; render() }
        undo = button("撤回采用") {
            if (draft.revert()) { updateEditor(); changed(draft.source); status.text = "已恢复完整原文"; render() }
        }
        button("管理离线文字包") { stop(); models() }
        label("整理只允许调整标点、口头语和紧邻重复词。字符检查无法保证语义正确，请核对姓名、数字、否定和条件关系。", "整理核对提醒").textSize = 12f
        render()
    }
    private fun label(text: String, desc: String) = TextView(context).apply {
        this.text = text; contentDescription = desc; textSize = 14f; setTextColor(palette.muted)
        setPadding(0, dp(10), 0, dp(8)); addView(this)
    }
    private fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title; contentDescription = title; textSize = 15f; isAllCaps = false; minHeight = dp(48)
        stateListAnimator = null; setTextColor(palette.highlight); background = palette.surface(palette.key, true)
        setOnClickListener { action() }; addView(this, LayoutParams(-1, -2))
    }
    private fun updateEditor() { setting = true; editor.setText(draft.source); setting = false }
    private fun run() {
        if (busy) return
        if (!TextModels.ready(context)) { status.text = "请先下载离线文字包，原文会保留"; models(); return }
        val source = draft.source
        val valid = runCatching { JSONObject(NativeBridge.textTool(source, "", false)).getString("prompt") }
        if (valid.isFailure) { status.text = valid.exceptionOrNull()?.message ?: "请检查原文长度"; return }
        stop(); val request = generation; busy = true; draft.clear()
        status.text = "正在本机加载并整理…可随时取消"; render()
        client = TextClient(context) { answer ->
            if (request == generation && draft.source == source) {
                busy = false
                if (answer.has("error")) status.text = "未完成：${answer.optString("error")}"
                else {
                    draft.offer(source, answer.optString("text"), answer.optBoolean("accepted"))
                    status.text = if (draft.accepted) "已完成 · ${answer.optLong("totalMs")} ms · 请核对后采用"
                        else "请保留原文：${answer.optString("reason")}"
                }
                render()
            }
        }.also { it.run(source) }
    }
    private fun render() {
        generate.isEnabled = !busy; cancel.visibility = if (busy) View.VISIBLE else View.GONE
        output.text = draft.result.ifEmpty { "整理结果会显示在这里，原文不会被自动替换。" }
        adopt.isEnabled = !busy && draft.accepted
        adopt.alpha = if (adopt.isEnabled) 1f else .45f
        generate.alpha = if (generate.isEnabled) 1f else .45f
        keep.visibility = if (draft.result.isEmpty()) View.GONE else View.VISIBLE
        undo.visibility = if (draft.undo == null) View.GONE else View.VISIBLE
    }
    private fun stop() { generation++; client?.close(); client = null; busy = false }
    override fun close() { if (busy) status.text = "已暂停，原文保留"; stop(); render() }
}
