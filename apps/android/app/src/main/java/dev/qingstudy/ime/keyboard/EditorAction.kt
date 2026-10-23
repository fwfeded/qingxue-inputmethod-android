//! 普通回车与语音右侧动作共用宿主语义，禁用动作时按换行处理。
package dev.qingstudy.ime

import android.view.inputmethod.EditorInfo

object EditorAction {
    fun action(info: EditorInfo?): Int = if (info == null ||
        info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) EditorInfo.IME_ACTION_NONE
        else info.imeOptions and EditorInfo.IME_MASK_ACTION

    fun label(info: EditorInfo?): String = when (action(info)) {
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_DONE -> "完成"
        EditorInfo.IME_ACTION_NEXT -> "继续"
        EditorInfo.IME_ACTION_PREVIOUS -> "后退"
        else -> "换行"
    }
}
