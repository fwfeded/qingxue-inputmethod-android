// 旋转时保留后台操作和不可变预览；进程被回收后需重新选择文件。
package dev.qingstudy.ime

import org.json.JSONObject

class PhraseBackupState {
    var busy = false
    var abandoned = false
    var message = ""
    var expected = "[]"
    var preview: JSONObject? = null
    var changed: (() -> Unit)? = null
}
