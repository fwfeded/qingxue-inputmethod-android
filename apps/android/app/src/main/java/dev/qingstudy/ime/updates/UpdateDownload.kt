//! 限长流式下载，取消、网络策略变化或散列不符均清除半成品。
package dev.qingstudy.ime

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object UpdateDownload {
    fun copy(input: InputStream, file: File, release: UpdateRelease, cancelled: AtomicBoolean,
             networkAllowed: () -> Boolean, progress: (Int) -> Unit) {
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!cancelled.get()) { "已取消下载" }
                    check(networkAllowed()) { "网络已变化，下载已停止；请重新选择网络后重试" }
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= release.bytes) { "安装包大小不符" }
                    digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    progress((count * 100 / release.bytes).toInt())
                }
            }
            require(count == release.bytes && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包完整性校验失败" }
            check(!cancelled.get()) { "已取消下载" }
            complete = true
        } finally { if (!complete) file.delete() }
    }
}
