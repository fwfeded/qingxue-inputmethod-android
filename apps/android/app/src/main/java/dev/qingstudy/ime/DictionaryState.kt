// 旋转时保留词库操作状态；后台任务只在用户选择后启动一次。
package dev.qingstudy.ime

import org.json.JSONArray

class DictionaryState {
    var busy = false
    var message = ""
    var rows = JSONArray()
    var changed: (() -> Unit)? = null
}
