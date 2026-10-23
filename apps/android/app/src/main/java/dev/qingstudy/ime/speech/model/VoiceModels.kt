//! 可选语音包的私有存储；只启用完整校验文件，保留升级前已准备的模型。
package dev.qingstudy.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

object VoiceModels {
    const val totalBytes: Long = 240193589L
    const val description = "SenseVoice Small int8 · 中文、英文、日语、韩语、粤语 · 完全离线"
    private val files get() = VoiceModelManifest.files
    fun directory(context: Context): File = File(context.filesDir, "voice/models")
    private fun marker(directory: File): String = buildString {
        append(VoiceModelManifest.version)
        files.forEach { spec ->
            append('\n').append(spec.name).append(':').append(spec.bytes).append(':').append(spec.sha256)
                .append(':').append(File(directory, spec.name).lastModified())
        }
    }
    fun isReady(context: Context): Boolean {
        val target = directory(context)
        if (ready(target)) return true
        val root = target.parentFile!!
        if (target.exists() || !ready(File(root, "previous"))) return false
        // 激活两次rename之间被杀进程时，实际录音入口也能恢复旧包；UI读取不等待另一个下载任务。
        return runCatching {
            RandomAccessFile(File(root, "operation.lock"), "rw").use { file ->
                val lock = file.channel.tryLock() ?: return@use false
                lock.use { recover(context); ready(target) }
            }
        }.getOrDefault(false)
    }
    private fun recover(context: Context) {
        val target = directory(context)
        val previous = File(target.parentFile, "previous")
        if (!target.exists() && ready(previous)) check(previous.renameTo(target)) { "无法恢复已准备的离线包" }
    }
    private fun ready(directory: File): Boolean = try {
        val stamp = File(directory, "verified")
        stamp.isFile && stamp.length() < 2048 && files.all { spec ->
            File(directory, spec.name).let { it.isFile && it.length() == spec.bytes }
        } && stamp.readText() == marker(directory)
    } catch (_: Exception) { false }

    fun prepare(context: Context, canceled: () -> Boolean = { false }, progress: (Int) -> Unit = {}) {
        locked(context, canceled) {
            checkCanceled(canceled)
            check(isReady(context)) { "请先在语音设置下载离线语音包" }
            progress(100)
        }
    }
    fun download(context: Context, wifiOnly: Boolean, canceled: () -> Boolean, progress: (Int, String) -> Unit) {
        locked(context, canceled) {
            if (isReady(context)) { progress(100, "离线语音包已就绪"); return@locked }
            val stage = File(directory(context).parentFile, "download")
            check(stage.isDirectory || stage.mkdirs()) { "无法创建下载目录" }
            var finished = 0L
            var lastCheck = 0L
            val network = context.getSystemService(ConnectivityManager::class.java)
            fun wifi() = network.allNetworks.firstOrNull {
                network.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            fun checkpoint() {
                checkCanceled(canceled)
                if (wifiOnly && SystemClock.elapsedRealtime() - lastCheck > 500) {
                    while (wifi() == null) {
                        progress((finished * 99 / totalBytes).toInt(), "等待Wi-Fi；离开页面会暂停下载")
                        checkCanceled(canceled); Thread.sleep(300)
                    }
                    lastCheck = SystemClock.elapsedRealtime()
                }
            }
            files.forEach { spec ->
                VoiceModelTransfer.fetch(File(stage, spec.name), spec, ::checkpoint, { received ->
                    progress(((finished + received) * 99 / totalBytes).toInt(), "下载中 · ${(finished + received) / 1024 / 1024} / ${totalBytes / 1024 / 1024} MiB")
                }, { url ->
                    checkpoint()
                    // 将连接绑定到Wi-Fi，掉线时失败；不会随默认网络切换消耗移动流量。
                    if (wifiOnly) (wifi() ?: error("Wi-Fi已断开，片段已保留")).openConnection(url) as HttpsURLConnection
                    else url.openConnection() as HttpsURLConnection
                })
                finished += spec.bytes
            }
            checkCanceled(canceled); activate(context, stage, canceled)
            progress(100, "校验完成，可以离线使用")
        }
    }
    internal fun activate(context: Context, stage: File, canceled: () -> Boolean = { false }) {
        require(stage.name == "download" && stage.canonicalFile.parentFile == directory(context).parentFile!!.canonicalFile)
        check(files.all { VoiceModelTransfer.verify(File(stage, it.name), it) { checkCanceled(canceled) } }) { "离线包不完整或校验失败" }
        checkCanceled(canceled)
        FileOutputStream(File(stage, "verified")).use { it.write(marker(stage).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        checkCanceled(canceled)
        val target = directory(context)
        val previous = File(target.parentFile, "previous")
        check(!previous.exists() || previous.deleteRecursively()) { "无法清理旧模型副本" }
        val existed = target.exists()
        if (existed) check(target.renameTo(previous)) { "无法保留旧模型" }
        if (!stage.renameTo(target)) {
            if (existed) previous.renameTo(target)
            error("无法启用语音包，原模型已保留")
        }
        previous.deleteRecursively()
    }
    fun hasPartial(context: Context) = File(directory(context).parentFile, "download").listFiles()?.any { it.length() > 0 } == true
    fun deletePartial(context: Context) = locked(context, { false }) {
        val partial = File(directory(context).parentFile, "download")
        check(!partial.exists() || partial.deleteRecursively()) { "无法删除下载片段" }
    }
    fun delete(context: Context) = locked(context, { false }) {
        val root = directory(context).parentFile!!
        listOf("models", "staging", "download", "import", "previous").forEach { name ->
            val target = File(root, name)
            check(!target.exists() || target.deleteRecursively()) { "无法删除语音包" }
        }
    }
    internal fun <T> locked(context: Context, canceled: () -> Boolean, action: () -> T): T {
        val root = directory(context).parentFile!!
        check(root.isDirectory || root.mkdirs()) { "无法创建语音目录" }
        RandomAccessFile(File(root, "operation.lock"), "rw").use { file ->
            while (true) {
                checkCanceled(canceled)
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return lock.use {
                    checkCanceled(canceled)
                    recover(context)
                    action()
                }
                Thread.sleep(40)
            }
        }
    }
    private fun checkCanceled(canceled: () -> Boolean) {
        if (canceled() || Thread.currentThread().isInterrupted) throw CancellationException("语音包下载已暂停")
    }
}
