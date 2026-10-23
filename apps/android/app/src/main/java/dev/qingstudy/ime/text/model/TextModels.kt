//! 文字模型单独按需安装；固定大小/散列、断点续传、跨进程互斥，与语音包和学习数据隔离。
package dev.qingstudy.ime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CancellationException

internal object TextModels {
    val spec = VoiceModelFile("qwen2.5-1.5b-instruct-q4_k_m.gguf", 1117320736L,
        "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf")
    val alternate = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/${spec.name}"
    fun directory(context: Context) = File(context.filesDir, "offline-text").apply { mkdirs() }
    fun model(context: Context) = File(directory(context), spec.name)
    fun ready(context: Context) = model(context).length() == spec.bytes &&
        runCatching { File(directory(context), "verified").readText() == spec.sha256 }.getOrDefault(false)
    fun partial(context: Context) = File(directory(context), "model.part").length()
    fun <T> locked(context: Context, canceled: () -> Boolean, action: () -> T): T {
        RandomAccessFile(File(directory(context), "operation.lock"), "rw").use { file ->
            while (true) {
                checkpoint(canceled)
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return lock.use { checkpoint(canceled); action() }
                Thread.sleep(50)
            }
        }
    }
    private fun checkpoint(canceled: () -> Boolean) { if (canceled() || Thread.currentThread().isInterrupted) throw CancellationException("已暂停") }
    fun download(context: Context, alternateSource: Boolean, canceled: () -> Boolean, progress: (Int) -> Unit) = locked(context, canceled) {
        if (!ready(context)) {
            val root = directory(context)
            check(root.usableSpace >= (spec.bytes - partial(context)).coerceAtLeast(0) + 64L * 1024 * 1024) { "存储空间不足，请至少预留 1.2 GB" }
            val part = File(root, "model.part")
            VoiceModelTransfer.fetch(part, spec.copy(url = if (alternateSource) alternate else spec.url), { checkpoint(canceled) }, { progress((it * 100 / spec.bytes).toInt()) })
            checkpoint(canceled)
            check(part.renameTo(model(context))) { "无法安装文字包" }
            File(root, "verified").writeText(spec.sha256)
        }
    }
    fun delete(context: Context, canceled: () -> Boolean) = locked(context, canceled) {
        for (name in listOf("verified", spec.name, "model.part")) {
            val file = File(directory(context), name)
            check(!file.exists() || file.delete()) { "无法删除文字包，请结束整理后重试" }
        }
    }
}
