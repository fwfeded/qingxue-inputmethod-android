// 学习备份经 Rust 生成快照，再通过用户授权的文档 URI 保存。
package dev.qingstudy.ime

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

object LearningBackupFiles {
    fun directory(context: Context): File = File(JSONObject(NativeBridge.learning(
        File(context.filesDir, "learning").absolutePath, "directory", "")).getString("directory"))

    fun stage(context: Context, uri: Uri): File {
        val file = File.createTempFile("learning-restore-", ".json", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法读取所选备份" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        check(total <= 32L * 1024 * 1024) { "备份文件超过 32 MB" }
                        output.write(buffer, 0, size)
                    }
                }
            }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    fun inspect(context: Context, file: File): JSONObject = operation(context, "inspect", file)
    fun restore(context: Context, file: File): JSONObject = operation(context, "restore", file)
    private fun operation(context: Context, action: String, file: File): JSONObject = JSONObject(
        NativeBridge.learning(File(context.filesDir, "learning").absolutePath, action, file.absolutePath))

    fun export(context: Context, uri: Uri): JSONObject {
        val staging = File.createTempFile("learning-backup-", ".json", context.cacheDir)
        try {
            val result = JSONObject(NativeBridge.exportLearning(File(context.filesDir, "learning").absolutePath, staging.absolutePath))
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                checkNotNull(output) { "无法写入所选位置" }
                staging.inputStream().use { it.copyTo(output) }
            }
            return result
        } finally { staging.delete() }
    }
}
