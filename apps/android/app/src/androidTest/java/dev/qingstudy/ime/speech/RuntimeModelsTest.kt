//! 在模拟器已有真实模型上验证可选包边界；原模型移到测试备份并在finally恢复。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RuntimeModelsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(test: (File) -> Unit) {
        assertTrue("请先提供已验证真实模型", VoiceModels.isReady(context))
        val original = VoiceModels.directory(context)
        val backup = File(context.cacheDir, "runtime-model-backup")
        check(!backup.exists()); check(original.renameTo(backup))
        try { test(backup) } finally {
            VoiceModels.delete(context)
            check(backup.renameTo(original))
        }
    }
    @Test fun verifiedInstallAndDeleteAreScoped() = isolated { backup ->
        val unrelated = File(context.filesDir, "runtime-test-unrelated").apply { writeText("preserve") }
        try {
            val stage = File(VoiceModels.directory(context).parentFile, "download")
            backup.copyRecursively(stage, overwrite = true)
            var checks = 0
            assertTrue(runCatching { VoiceModels.activate(context, stage) { ++checks > 4 } }.exceptionOrNull() is CancellationException)
            assertFalse("校验中取消不得启用", VoiceModels.isReady(context))
            assertTrue(stage.isDirectory)
            VoiceModels.locked(context, { false }) { VoiceModels.activate(context, stage) }
            var progress = -1
            VoiceModels.prepare(context) { progress = it }
            assertEquals(100, progress); assertTrue(VoiceModels.isReady(context))
            assertEquals(VoiceModels.totalBytes, VoiceModels.directory(context).listFiles()!!
                .filter { it.name != "verified" }.sumOf { it.length() })
            val tokens = File(VoiceModels.directory(context), "tokens.txt")
            assertTrue(tokens.setLastModified(tokens.lastModified() - 5000))
            assertFalse(VoiceModels.isReady(context))
            VoiceModels.delete(context)
            assertFalse(VoiceModels.isReady(context)); assertEquals("preserve", unrelated.readText())
        } finally { unrelated.delete() }
    }
    @Test fun missingPackageNeverDownloadsImplicitlyAndPartialCannotActivate() = isolated { _ ->
        val failure = runCatching { VoiceModels.prepare(context) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(File(VoiceModels.directory(context).parentFile, "download").exists())
        assertTrue(runCatching { VoiceModels.prepare(context, { true }) }.exceptionOrNull() is CancellationException)
        val stage = File(VoiceModels.directory(context).parentFile, "download").apply { mkdirs() }
        File(stage, "tokens.txt").writeText("invalid")
        assertTrue(runCatching { VoiceModels.activate(context, stage) }.isFailure)
        assertFalse(VoiceModels.isReady(context)); assertFalse(VoiceModels.directory(context).exists())
    }
    @Test fun interruptedActivationRestoresPreviousWithoutNetwork() {
        assertTrue(VoiceModels.isReady(context))
        val target = VoiceModels.directory(context)
        val previous = File(target.parentFile, "previous")
        check(!previous.exists()); check(target.renameTo(previous))
        try {
            assertTrue("录音入口的isReady必须自动恢复旧包", VoiceModels.isReady(context))
            VoiceModels.prepare(context)
            assertTrue(VoiceModels.isReady(context)); assertFalse(previous.exists())
            val stage = File(target.parentFile, "download").apply { mkdirs() }
            File(stage, "tokens.txt").writeText("corrupt")
            assertTrue(runCatching { VoiceModels.activate(context, stage) }.isFailure)
            assertTrue("坏包不能覆盖旧包", VoiceModels.isReady(context))
            assertTrue(VoiceModels.hasPartial(context))
            VoiceModels.deletePartial(context)
            assertFalse(stage.exists()); assertTrue(VoiceModels.isReady(context))
        } finally { if (!target.exists()) previous.renameTo(target) }
    }
}
