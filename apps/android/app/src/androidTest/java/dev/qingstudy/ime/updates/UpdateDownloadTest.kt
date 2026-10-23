//! 下载失败/取消/换网不会保留半包，也不接触个人数据。
package dev.qingstudy.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class UpdateDownloadTest {
    private val bytes = ByteArray(200000) { (it % 233).toByte() }
    private val release = UpdateRelease(37, "test", "beta", "x86_64", 26, "https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/v1/app.apk",
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "b".repeat(64), "")
    private val file get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "download-test.partial")
    @Test fun copyValidAndRejectCorruptOrTruncated() {
        try {
            UpdateDownload.copy(bytes.inputStream(), file, release, AtomicBoolean(false), { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            for (bad in listOf(bytes.copyOf(bytes.size - 10), bytes.copyOf().also { it[8] = 42 }, bytes + byteArrayOf(9))) {
                assertTrue(runCatching { UpdateDownload.copy(bad.inputStream(), file, release, AtomicBoolean(false), { true }) { } }.isFailure)
                assertFalse(file.exists())
            }
        } finally { file.delete() }
    }
    @Test fun cancelAndNetworkChangeRemovePartial() {
        val cancelled = AtomicBoolean(false)
        assertTrue(runCatching { UpdateDownload.copy(bytes.inputStream(), file, release, cancelled, { true }) { cancelled.set(true) } }.isFailure)
        assertFalse(file.exists())
        var reads = 0
        assertTrue(runCatching { UpdateDownload.copy(bytes.inputStream(), file, release, AtomicBoolean(false), { reads++ == 0 }) { } }.isFailure)
        assertFalse(file.exists())
    }
    @Test fun providerRejectsOtherFilesAndWriteModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (path in listOf("/../../files/prefs.json", "/update.apk/../private", "/private")) {
            assertTrue(runCatching { context.contentResolver.openFileDescriptor(android.net.Uri.parse("content://${context.packageName}.updates$path"), "r") }.isFailure)
        }
        assertTrue(runCatching { context.contentResolver.openFileDescriptor(android.net.Uri.parse("content://${context.packageName}.updates/update.apk"), "w") }.isFailure)
    }
}
