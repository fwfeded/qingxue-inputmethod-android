// 文档 URI 读写及设置原子替换；格式校验和预览复用 Rust。
package dev.qingstudy.ime

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object PhraseBackupFiles {
    fun current(context: Context): String = Preferences.read(context).opt("customPhrases")?.toString() ?: "[]"
    fun inspect(context: Context, uri: Uri): JSONObject {
        val bytes = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "无法读取所选文件" }
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= 2 * 1024 * 1024) { "短语备份文件最多 2 MiB" }
                bytes.write(buffer, 0, count)
            }
        }
        val source = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("短语备份必须是有效的 UTF-8 文本", error)
        }
        return JSONObject(NativeBridge.phraseFile("inspect", source))
    }
    fun export(context: Context, uri: Uri, source: String): Int {
        // 先校验并生成完整内容，再打开输出，避免无效设置清空用户选中的文件。
        val result = JSONObject(NativeBridge.phraseFile("export", source))
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            checkNotNull(output) { "无法写入所选位置" }
            output.write(result.getString("document").toByteArray(Charsets.UTF_8))
        }
        return result.getInt("count")
    }
    // 在主线程确认时调用，保留预览后变更的其他设置，但不覆盖新短语。
    fun replace(context: Context, expected: String, preview: JSONObject) {
        val fresh = Preferences.read(context)
        check((fresh.opt("customPhrases")?.toString() ?: "[]") == expected) { "短语已变化，请重新选择备份并预览" }
        val rows = JSONObject(NativeBridge.phrases(preview.getJSONArray("phrases").toString())).getJSONArray("phrases")
        Preferences.save(context, fresh.put("customPhrases", rows))
    }
}
