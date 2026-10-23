//! 显式网络验收：官方下载、中止和HTTP续传；不与断网语音套件混跑。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelNetworkTest {
    @Test fun officialDownloadCancelsResumesAndActivatesVerifiedFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = VoiceModels.directory(context)
        val backup = File(context.cacheDir, "network-model-backup")
        check(!backup.exists())
        val hadOriginal = target.exists()
        if (hadOriginal) check(target.renameTo(backup))
        val began = SystemClock.elapsedRealtime()
        try {
            var cancel = false
            val result = runCatching { VoiceModels.download(context, true, { cancel }) { p, _ -> if (p >= 1) cancel = true } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(VoiceModels.isReady(context))
            val partial = File(target.parentFile, "download/model.int8.onnx").length()
            assertTrue(partial > 0 && partial < VoiceModelManifest.files[0].bytes)
            var progress = 0
            VoiceModels.download(context, true, { false }) { p, _ -> progress = p }
            assertEquals(100, progress); assertTrue(VoiceModels.isReady(context))
            VoiceModelManifest.files.forEach { assertTrue(VoiceModelTransfer.verify(File(target, it.name), it)) }
            File(context.getExternalFilesDir(null), "r5-download-evidence.json").writeText(JSONObject()
                .put("success", true).put("wifi_bound", true).put("partial_bytes_before_resume", partial)
                .put("download_ms", SystemClock.elapsedRealtime() - began).put("verified_bytes", VoiceModels.totalBytes).toString(2))
        } finally {
            if (hadOriginal) { VoiceModels.delete(context); check(backup.renameTo(target)) }
        }
    }
}
