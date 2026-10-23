// 系统文档 URI 与应用私有文件之间的流式传输；格式转换交给 Rust。
package dev.qingstudy.ime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object DictionaryFiles {
    val executor = Executors.newSingleThreadExecutor()
    fun manage(context: Context, action: String, args: JSONObject = JSONObject()): String =
        NativeBridge.dictionaries(File(context.filesDir, "learning").absolutePath, action, args.toString())

    fun import(context: Context, uri: Uri): JSONObject {
        val directory = File(context.cacheDir, "import-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val displayName = displayName(context, uri)
            val name = displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).trimStart('.').ifBlank { "词库.tsv" }
            val source = File(directory, name)
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法读取所选文件" }
                source.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 16 * 1024 * 1024) { "单本词库最大 16 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return JSONObject(manage(context, "import", JSONObject().put("path", source.absolutePath)))
        } finally { directory.deleteRecursively() }
    }


    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "词库.tsv"

    fun importSelected(context: Context, sources: List<Uri>): String {
        val unique = sources.distinct()
        require(unique.isNotEmpty()) { "未选择可读取的词库文件" }
        require(unique.size <= 32) { "一次最多选择 32 个词库文件，本次未导入。" }
        if (unique.size == 1) {
            val row = import(context, unique.single())
            return "已导入：${row.getString("name")}，${row.getInt("entries")} 条。"
        }
        var succeeded = 0
        val details = unique.map { uri ->
            val name = displayName(context, uri).take(120)
            runCatching { import(context, uri) }.fold(
                { row ->
                    succeeded++
                    "$name：成功，${row.getInt("entries")} 条（${row.getString("name").take(120)}）"
                },
                { error -> "$name：失败，${(error.message ?: "无法读取文件").take(300)}" }
            )
        }
        return "批量导入完成：成功 $succeeded 个，失败 ${unique.size - succeeded} 个。\n" + details.joinToString("\n")
    }

    fun export(context: Context, id: String, format: String, uri: Uri) {
        val staging = File.createTempFile("dictionary-export-", ".$format", context.cacheDir)
        try {
            manage(context, "export", JSONObject().put("id", id).put("format", format).put("path", staging.absolutePath))
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                checkNotNull(output) { "无法写入所选位置" }
                staging.inputStream().use { it.copyTo(output) }
            }
        } finally { staging.delete() }
    }
}
