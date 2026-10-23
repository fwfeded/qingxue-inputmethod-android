//! HTTPS断点读取与有界校验；只在设置页显式调用，不在录音启动时下载。
package dev.qingstudy.ime

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object VoiceModelTransfer {
    fun verify(file: File, spec: VoiceModelFile, checkpoint: () -> Unit = {}): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }
    fun fetch(file: File, spec: VoiceModelFile, checkpoint: () -> Unit, progress: (Long) -> Unit,
              connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
        checkpoint()
        if (verify(file, spec, checkpoint)) { progress(spec.bytes); return }
        if (file.exists() && file.length() >= spec.bytes) check(file.delete()) { "无法清理损坏的下载片段" }
        var offset = if (file.isFile) file.length() else 0L
        var address = spec.url
        var connection: HttpsURLConnection? = null
        try {
            for (redirect in 0..8) {
                checkpoint()
                require(address.startsWith("https://")) { "模型地址必须使用HTTPS" }
                val next = connect(URL(address))
                connection = next
                next.connectTimeout = 15000; next.readTimeout = 8000
                next.instanceFollowRedirects = false
                next.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) next.setRequestProperty("Range", "bytes=$offset-")
                if (next.responseCode in listOf(301, 302, 303, 307, 308)) {
                    address = URL(URL(address), next.getHeaderField("Location") ?: error("下载地址缺少跳转目标")).toString()
                    next.disconnect(); connection = null
                    check(redirect < 8) { "模型地址跳转过多" }
                } else break
            }
            val active = connection ?: error("无法连接模型下载地址")
            val code = active.responseCode
            check(code == 200 || code == 206) { "模型下载失败（HTTP $code），请检查网络后重试" }
            if (code == 200) offset = 0L
            else check(active.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "下载断点不匹配" }
            val remaining = active.contentLengthLong
            check(remaining < 0 || offset + remaining == spec.bytes) { "模型下载大小不符" }
            var count = offset
            active.inputStream.use { input ->
                FileOutputStream(file, offset > 0).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkpoint(); val n = input.read(buffer); if (n < 0) break
                        check(count + n <= spec.bytes) { "模型下载超出预期大小" }
                        output.write(buffer, 0, n); count += n; progress(count)
                    }
                    output.fd.sync()
                }
            }
            checkpoint()
            check(file.length() == spec.bytes) { "下载中断，已保存片段；请继续下载" }
            check(verify(file, spec, checkpoint)) {
                file.delete()
                "模型校验失败，未启用；请重新下载"
            }
        } finally { connection?.disconnect() }
    }
}
