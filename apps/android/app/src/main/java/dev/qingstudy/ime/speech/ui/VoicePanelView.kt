package dev.qingstudy.ime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher

// V3 的等高语音层。左右松手操作只由长按产生，不把普通结束解释成发送。
class VoicePanelView(context: Context, private val action: (String, String) -> Unit) : LinearLayout(context) {
    private val palette = KeyboardPalette(context, KeyboardAppearance.read(context))
    private val status = TextView(context)
    private val editor = VoiceEditText(context)
    private val hold = VoiceHoldView(context, action)
    var zoneFeedback: ((View) -> Unit)? = null
    private val controls = LinearLayout(context)
    private val alternatives = LinearLayout(context)
    private val suggestions = android.widget.HorizontalScrollView(context)
    private val pause = button("暂停", "pause")
    private val sendEdit = button("发送", "send")
    private val organize = button("整理", "organize")
    private var settingText = false
    private var isHold = false
    private var target = "finish"
    private var actionName = "换行"
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    init {
        hold.feedback = { zoneFeedback?.invoke(hold) }
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        status.textSize = 13f; status.gravity = Gravity.CENTER; status.setTextColor(palette.muted)
        status.contentDescription = "语音状态"
        status.minHeight = dp(32); status.maxLines = 3
        addView(status, LayoutParams(-1, -2))
        editor.apply {
            textSize = 20f; setTextColor(palette.ink); setHintTextColor(palette.muted)
            hint = "请说话…"; contentDescription = "语音编辑文字"; gravity = Gravity.TOP
            showSoftInputOnFocus = false; background = null; setPadding(dp(24), dp(16), dp(24), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!settingText) action("edit", s.toString()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        editor.selection = { start, end -> if (!settingText && !isHold) action("selection", "$start:$end") }
        addView(editor, LayoutParams(-1, 0, 1f))
        addView(hold, LayoutParams(-1, 0, 1f))
        val help = button("选中文字 → 返回键盘修改", "keyboard")
        help.textSize = 12f
        controls.orientation = VERTICAL
        suggestions.addView(alternatives)
        alternatives.addView(help, LayoutParams(-1, dp(36)))
        val confirmation = LinearLayout(context)
        confirmation.addView(suggestions, LayoutParams(0, dp(36), 1f))
        confirmation.addView(organize, LayoutParams(dp(60), dp(36)))
        confirmation.addView(button("确认上屏", "finish"), LayoutParams(dp(92), dp(36)))
        controls.addView(confirmation)
        val row = LinearLayout(context)
        listOf(button("键盘", "keyboard"), button("撤回", "undo"), pause, button("退格", "delete"), sendEdit).forEach { row.addView(it, LayoutParams(0, dp(48), 1f)) }
        controls.addView(row)
        addView(controls, LayoutParams(-1, -2))
    }
    private fun button(label: String, command: String) = Button(context).apply {
        isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false
        text = label; contentDescription = "语音 $command"; textSize = 14f; isAllCaps = false
        stateListAnimator = null; elevation = 0f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; includeFontPadding = false
        setPadding(0, 0, 0, 0); setTextColor(palette.ink); background = palette.surface(palette.function, true)
        setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        setOnClickListener {
            if (command == "delete") {
                val start = editor.selectionStart.coerceAtLeast(0); val end = editor.selectionEnd.coerceAtLeast(0)
                val lo = minOf(start, end); val hi = maxOf(start, end)
                val from = if (lo == hi && lo > 0) lo - EditorDeletion.lastGraphemeLength(editor.text.substring(0, lo)) else lo
                editor.text.delete(from, hi)
            } else action(command, if (command == "keyboard") "${editor.selectionStart}:${editor.selectionEnd}" else "")
        }
    }
    fun mode(held: Boolean, enterLabel: String) {
        isHold = held; actionName = enterLabel; target = "finish"
        hold.visibility = if (held) View.VISIBLE else View.GONE
        status.visibility = if (held) View.GONE else View.VISIBLE
        editor.visibility = if (held) View.GONE else View.VISIBLE
        controls.visibility = if (held) View.GONE else View.VISIBLE
        editor.isFocusable = !held; editor.isFocusableInTouchMode = !held
        sendEdit.text = enterLabel; hold.mode(enterLabel)
    }
    fun recovery() {
        controls.removeAllViews()
        listOf("继续用键盘" to "keyboard", "改用本机离线" to "use-offline", "检查服务器连接" to "server-settings").forEach { (label, command) ->
            controls.addView(button(label, command), LayoutParams(-1, dp(42)))
        }
    }
    fun level(level: Float) { hold.level(level) }
    fun onHoldZoneChanged(callback: (Boolean) -> Unit) { hold.zoneChanged = callback }
    fun update(text: String, message: String, running: Boolean, processing: Boolean) {
        status.text = message
        hold.update(message,running,processing)
        if (editor.text.toString() != text) {
            settingText = true; editor.setText(text); editor.setSelection(editor.length()); settingText = false
        }
        pause.text = if (processing) "识别中" else if (running) "暂停" else "继续"
        pause.isEnabled = !processing
        organize.isEnabled = !running && text.isNotBlank()
    }
    fun hover(x: Float, y: Float): String {
        return hold.hover(x-hold.left,y-hold.top)
    }
    fun candidates(items: List<Pair<String, String>>) {
        alternatives.removeAllViews()
        if (items.isEmpty()) {
            alternatives.addView(button("选中文字 → 返回键盘修改", "keyboard").apply { textSize = 12f }, LayoutParams(-2, dp(36)))
            return
        }
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        val snapshot = editor.text.toString()
        for ((word, gloss) in items) alternatives.addView(TextView(context).apply {
            text = if (gloss.isEmpty()) word else "$word · $gloss"; textSize = 15f
            setTextColor(palette.ink); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "语音候选 $word"; isFocusable = true; background = palette.surface(palette.key, true)
            setOnClickListener {
                if (editor.text.toString() == snapshot && end > start &&
                    minOf(editor.selectionStart, editor.selectionEnd) == start && maxOf(editor.selectionStart, editor.selectionEnd) == end) {
                    editor.text.replace(start, end, word); editor.setSelection(start + word.length)
                    candidates(emptyList())
                }
            }
        }, LayoutParams(-2, dp(36)))
        suggestions.scrollTo(0, 0)
    }
}

private class VoiceEditText(context: Context) : EditText(context) {
    var selection: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); selection?.invoke(selStart, selEnd) }
}
