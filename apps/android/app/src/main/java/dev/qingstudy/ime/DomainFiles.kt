// 公开领域数据放在 Direct Boot 可读目录；并发复制使用唯一临时文件。
package dev.qingstudy.ime

import android.content.Context
import java.io.File

object DomainFiles {
    fun prepare(context: Context): File {
        val app = context.createDeviceProtectedStorageContext()
        val directory = File(app.filesDir, "engine-v1/domains").apply { mkdirs() }
        for (name in app.assets.list("engine/domains").orEmpty().filter { it.endsWith(".tsv") }) {
            val temporary = File.createTempFile("domain-", ".tmp", directory)
            try {
                app.assets.open("engine/domains/$name").use { input -> temporary.outputStream().use(input::copyTo) }
                check(temporary.renameTo(File(directory, name))) { "无法准备领域词库：$name" }
            } finally { temporary.delete() }
        }
        return directory
    }
}
