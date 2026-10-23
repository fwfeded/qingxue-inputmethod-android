//! HTTPS 请求只在青学源与 GitHub 资源域名间跳转，不降级到明文。
package dev.qingstudy.ime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UpdateTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    fun cancel() { client.dispatcher.cancelAll() }
    fun read(url: String, cancelled: AtomicBoolean, consume: (InputStream) -> Unit) {
        require(url == UpdateIndex.URL || UpdateIndex.packageUrl(url)) { "不可信的更新地址" }
        var next = url
        repeat(6) {
            check(!cancelled.get()) { "已取消" }
            val response = client.newCall(Request.Builder().url(next).header("User-Agent", "Qingxue-Android-Updates").build()).execute()
            response.use { result ->
                if (result.code in listOf(301, 302, 303, 307, 308)) {
                    val target = URI(next).resolve(result.header("Location") ?: error("发布源缺少跳转地址"))
                    require(target.scheme == "https" && target.userInfo == null && target.port == -1 &&
                        (target.host == "github.com" || target.host == "raw.githubusercontent.com" ||
                            target.host == "release-assets.githubusercontent.com" || target.host == "objects.githubusercontent.com")) { "发布源跳转到不可信地址" }
                    next = target.toString()
                } else {
                    check(result.isSuccessful) { "发布源暂时不可用（HTTP ${result.code}）" }
                    consume(result.body?.byteStream() ?: error("发布源返回空文件"))
                    return
                }
            }
        }
        error("发布源跳转次数过多")
    }
    fun index(cancelled: AtomicBoolean): ByteArray {
        var bytes = ByteArray(0)
        read(UpdateIndex.URL, cancelled) { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check(!cancelled.get()) { "已取消" }
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= UpdateIndex.MAX_INDEX) { "版本清单过大" }
                out.write(buffer, 0, count)
            }
            bytes = out.toByteArray()
        }
        return bytes
    }
}
