//! 在模拟器实际访问官方模型源并暂停/续传；只取片段，完整推理由既有同散列模型验证。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

class TextDownloadNetworkTest {
    @Test fun officialModelSourceAcceptsPauseAndRangeResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val part = File(context.cacheDir, "text-official-probe.part")
        val evidence = JSONObject()
        try {
            part.delete()
            for (limit in listOf(512 * 1024L, 1536 * 1024L)) {
                var stop = false
                val error = runCatching {
                    VoiceModelTransfer.fetch(part, TextModels.spec,
                        { if (stop) throw CancellationException() }, { if (it >= limit) stop = true })
                }.exceptionOrNull()
                assertTrue(error?.toString(), error is CancellationException)
                assertTrue(part.length() >= limit); assertTrue(part.length() < TextModels.spec.bytes)
            }
            assertFalse(VoiceModelTransfer.verify(part, TextModels.spec))
            evidence.put("url", TextModels.spec.url).put("downloaded_bytes", part.length()).put("pause_resume", true).put("complete_download_tested", false)
            File(context.getExternalFilesDir(null), "text-download-evidence.json").writeText(evidence.toString(2))
        } finally { part.delete() }
    }
}
