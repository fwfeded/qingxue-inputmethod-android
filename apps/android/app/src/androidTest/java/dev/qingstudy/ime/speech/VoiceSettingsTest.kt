package dev.qingstudy.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VoiceSettingsTest {
    @Test fun unavailableCredentialStorageReturnsDefaultsWithoutOpeningUnlockedFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val blocker = File.createTempFile("voice-locked", ".tmp", context.cacheDir)
        val inaccessible = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = File(blocker, "inaccessible")
        }
        try {
            val defaults = VoiceSettings.read(inaccessible)
            assertEquals("offline", defaults.getString("provider"))
            assertFalse(defaults.has("marker"))
            assertFalse(defaults.optBoolean("send_command"))
        }
        finally { blocker.delete() }
    }
    @Test fun concurrentReadsDoNotDiscardSavedSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = VoiceSettings.read(context).toString()
        val running = AtomicBoolean(true)
        val error = AtomicReference<Throwable>()
        VoiceSettings.save(context, "marker", "preserved")
        val reader = Thread {
            try {
                while (running.get()) {
                    assertEquals("preserved", VoiceSettings.read(context).getString("marker"))
                    Thread.yield()
                }
            } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            reader.start()
            repeat(60) { index ->
                VoiceSettings.save(context, "sequence", index)
                assertEquals(index, VoiceSettings.read(context).getInt("sequence"))
                VoiceSettings.save(context, "blank_lines", index % 2 == 0)
            }
            assertNull(error.get())
        } finally {
            running.set(false); reader.join(5000)
            assertFalse(reader.isAlive)
            File(context.filesDir, "voice-settings.json").writeText(original)
        }
    }
}
