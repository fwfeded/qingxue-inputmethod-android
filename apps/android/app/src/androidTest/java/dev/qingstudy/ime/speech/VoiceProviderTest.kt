//! 来源迁移在隔离文件目录测试，不改变模拟器用户设置。
package dev.qingstudy.ime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VoiceProviderTest {
    @Test fun explicitComputerSourceSurvivesSettingsReadAndKeepsFormatting() = isolated { context ->
        VoiceSettings.save(context, "provider", "offline")
        VoiceSettings.save(context, "blank_lines", true)
        VoiceSettings.save(context, "provider", "online")
        assertEquals("online", VoiceSettings.read(context).getString("provider"))
        assertTrue(VoiceSettings.read(context).getBoolean("blank_lines"))
    }
    private fun isolated(body: (ContextWrapper) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "provider-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        try { body(context) } finally { dir.deleteRecursively() }
    }
    @Test fun freshInstallKeepsSystemAfterOptionalPackageDownload() = isolated { context ->
        assertEquals("system", VoiceSettings.read(context).getString("provider"))
        File(context.filesDir, "voice/models").mkdirs()
        File(context.filesDir, "voice/models/verified").writeText("presence marker; not a real model")
        assertEquals("system", VoiceSettings.read(context).getString("provider"))
        VoiceSettings.save(context, "entry", false)
        assertEquals("system", VoiceSettings.read(context).getString("provider"))
        VoiceSettings.save(context, "provider", "offline")
        assertEquals("offline", VoiceSettings.read(context).getString("provider"))
    }
    @Test fun legacySettingsRemainOfflineUntilExplicitChoice() = isolated { context ->
        File(context.filesDir, "voice-settings.json").writeText("{\"entry\":false}")
        assertEquals("offline", VoiceSettings.read(context).getString("provider"))
        assertFalse(VoiceSettings.read(context).getBoolean("entry"))
        VoiceSettings.save(context, "provider", "system")
        assertEquals("system", VoiceSettings.read(context).getString("provider"))
    }
    @Test fun legacyPreparedPackageAlsoKeepsOfflineChoice() = isolated { context ->
        File(context.filesDir, "voice/models").mkdirs()
        File(context.filesDir, "voice/models/verified").writeText("presence marker; not a real model")
        assertEquals("offline", VoiceSettings.read(context).getString("provider"))
        File(context.filesDir, "voice/models/verified").delete()
        assertEquals("offline", VoiceSettings.read(context).getString("provider"))
    }
    @Test fun failedMigrationWriteNeverChangesLegacySourceToSystem() = isolated { context ->
        File(context.filesDir, "voice-settings.json").writeText("{\"entry\":false}")
        val blocked = File(context.filesDir, "voice-settings.json.new").apply { mkdirs() }
        File(blocked, "block-write").writeText("keep directory nonempty")
        assertEquals("offline", VoiceSettings.read(context).getString("provider"))
        assertFalse(VoiceSettings.read(context).getBoolean("entry"))
    }
}
