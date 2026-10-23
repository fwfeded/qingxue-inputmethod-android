// 数据加载和每次候选查询都在工作线程执行，避免阻塞键盘主线程。
package dev.qingstudy.ime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File

class EngineWorker(context: Context, private val ready: (String?) -> Unit) {
    private val thread = HandlerThread("qingjian-engine").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var startupError: Throwable? = null
    @Volatile private var closed = false

    init {
        // 这里只存 APK 自带的公开词库，不存用户输入；开机尚未解锁时也必须能创建 IME。
        val app = context.applicationContext.createDeviceProtectedStorageContext()
        worker.post {
            val error = runCatching {
                val directory = File(app.filesDir, "engine-v1").apply { mkdirs() }
                for (name in listOf("dict.tsv", "glossary-en.tsv", "english.tsv", "glossary-zh.tsv", "emoji-zh.tsv", "emoji-en.tsv", "lm.qj", "levels-en.tsv")) {
                    val destination = File(directory, name)
                    // 每次服务创建替换到临时文件，避免中断写入或 APK 升级留下旧数据。
                    // 服务重建可与旧 Worker 重叠；每个任务必须使用自己的临时文件。
                    val temporary = File.createTempFile("$name-", ".tmp", directory)
                    try {
                        app.assets.open("engine/$name").use { input -> temporary.outputStream().use(input::copyTo) }
                        check(temporary.renameTo(destination)) { "无法保存词库：$name" }
                    } catch (error: Exception) {
                        if (name != "levels-en.tsv") throw error
                        destination.delete()
                        android.util.Log.w("QingstudyEngine", "等级表不可用；继续基础输入", error)
                    } finally { temporary.delete() }
                }
                DomainFiles.prepare(app)
                handle = NativeBridge.create(directory.absolutePath)
                check(handle != 0L) { "引擎未能启动" }
            }.exceptionOrNull()
            startupError = error
            if (error != null) android.util.Log.e("QingstudyEngine", "Engine initialization failed", error)
            main.post { if (!closed) ready(error?.message) }
        }
    }

    fun step(action: String, argument: String = "", callback: (Result<Frame>) -> Unit) {
        if (closed) return
        worker.post {
            val result = runCatching {
                check(handle != 0L) { "引擎尚未就绪：${startupError?.message ?: "正在加载"}" }
                Frame.parse(NativeBridge.step(handle, action, argument))
            }
            main.post { if (!closed) callback(result) }
        }
    }

    fun close() {
        closed = true
        worker.post {
            if (handle != 0L) NativeBridge.close(handle)
            handle = 0
            thread.quitSafely()
        }
    }

    fun voiceAlternatives(word: String, callback: (List<Pair<String, String>>) -> Unit) {
        if (closed) return
        worker.post {
            val items = runCatching {
                val list = org.json.JSONObject(NativeBridge.step(handle, "voice-alternatives", word)).getJSONArray("alternatives")
                (0 until list.length()).map { list.getJSONArray(it).let { row -> row.getString(0) to row.getString(1) } }
            }.getOrDefault(emptyList())
            main.post { if (!closed) callback(items) }
        }
    }
}
