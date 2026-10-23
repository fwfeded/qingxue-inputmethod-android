//! 使用真实下载器验证断点、暂停、错误和散列；测试网络连接仅由测试注入。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

class TextModelTransferTest {
    private class Connection(url: URL, val data: ByteArray, val wrongRange: Boolean = false) : HttpsURLConnection(url) {
        val offset get() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        override fun getResponseCode() = if (offset == 0) 200 else 206
        override fun getContentLengthLong() = (data.size - offset).toLong()
        override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes ${if (wrongRange) 0 else offset}-${data.size - 1}/${data.size}" else null
        override fun getInputStream() = ByteArrayInputStream(data, offset, data.size - offset)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun pauseResumeAndCorruptDownloadNeverActivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "text-transfer-test.part")
        val data = ByteArray(700000) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val spec = VoiceModelFile("fixture", data.size.toLong(), sha, "https://example.test/model")
        var pause = false
        try {
            file.delete()
            val error = runCatching { VoiceModelTransfer.fetch(file, spec, { if (pause) throw CancellationException() }, { if (it > 100000) pause = true }, { Connection(it, data) }) }.exceptionOrNull()
            assertTrue(error is CancellationException); assertTrue(file.length() in 1 until data.size.toLong())
            assertFalse(VoiceModelTransfer.verify(file, spec))
            assertTrue(runCatching { VoiceModelTransfer.fetch(file, spec, {}, {}, { Connection(it, data, true) }) }.isFailure)
            VoiceModelTransfer.fetch(file, spec, {}, {}, { Connection(it, data) })
            assertTrue(VoiceModelTransfer.verify(file, spec))
            file.delete()
            assertTrue(runCatching { VoiceModelTransfer.fetch(file, spec.copy(sha256 = "0".repeat(64)), {}, {}, { Connection(it, data) }) }.isFailure)
            assertFalse(file.exists())
        } finally { file.delete() }
    }
}
