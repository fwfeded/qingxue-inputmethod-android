// R3 独立试打页；真实系统输入框用于体验候选、符号和释义。
package dev.qingstudy.ime

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

class TryInputActivity : Activity() {
    private lateinit var editor: EditText
    private var openedKeyboard = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = SettingsUi(this)
        ui.label("输入 nihao，按空格选词。\n试试符号、表情，或长按候选查看完整释义。", 14f)
        editor = EditText(this).apply {
            id = R.id.test_input; hint = "点击这里开始输入"; contentDescription = "测试输入框"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 20f; minLines = 3; gravity = Gravity.TOP or Gravity.START
            setTextColor(ui.palette.ink); setHintTextColor(ui.palette.muted)
            background = ui.palette.surface(ui.palette.key)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        ui.box.addView(editor, LinearLayout.LayoutParams(-1, -2))
        ui.button("清空试打内容") {
            editor.setText(""); editor.requestFocus()
            getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        ui.show("试用键盘") { finish() }
        editor.requestFocus()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !openedKeyboard) {
            openedKeyboard = true
            editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
}
