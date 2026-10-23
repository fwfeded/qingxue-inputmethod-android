// 旋转屏幕时保留后台操作与已校验的本机临时副本。
package dev.qingstudy.ime

import java.io.File

class LearningBackupState {
    var busy = false
    var abandoned = false
    var message = ""
    var preview: File? = null
    var rows = 0
    var tables = 0
    var changed: (() -> Unit)? = null
}
