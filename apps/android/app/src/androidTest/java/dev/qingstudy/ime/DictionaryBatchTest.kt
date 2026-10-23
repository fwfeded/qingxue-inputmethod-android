// 真实系统选择器多选、混合结果、旋转与取消；导入后实际输入。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class DictionaryBatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun rows() = JSONArray(DictionaryFiles.manage(context, "list"))
    private fun ids() = rows().let { data -> (0 until data.length()).map { data.getJSONObject(it).getString("id") }.toSet() }
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 2000))
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        Thread.sleep(1000)
        device.wait(Until.findObject(By.text(text).enabled(true)), 10000)!!.click()
    }
    private fun picker() {
        tap("导入词库文件")
        assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000))
        device.findObject(UiSelector().description("Show roots")).click()
        device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.res("android", "title").text("Downloads")), 10000))
        Thread.sleep(500)
        device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
        device.waitForIdle()
        tap("qstudy-batch-run")
    }
    @Test fun multipleDocumentsContinueAfterFailureAndSurviveRotation() {
        val saved = Preferences.read(context)
        val original = ids()
        val release = CountDownLatch(1)
        val fixtures = mutableListOf<Uri>()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            device.executeShellCommand("mkdir -p /sdcard/Download/qstudy-batch-run")
            for ((name, body) in listOf(
                "01-good.tsv" to "\uFEFF青学星穹\tqing xue xing qiong\t900000\n",
                "02-bad.tsv" to "# qingjian-dictionary-metadata-v1: {broken\n你好\tni hao\t9000\n",
                "03-good.dict.yaml" to "---\nname: batch-rime\n...\n你好\tni hao\t900000\n"
            )) {
                device.executeShellCommand("rm -f /sdcard/Download/qstudy-batch-run/$name")
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.')) ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/qstudy-batch-run")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                fixtures.add(uri)
                context.contentResolver.openOutputStream(uri)!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            context.startActivity(Intent(context, DictionaryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("导入词库文件").enabled(true)), 10000))
            picker()
            device.wait(Until.findObject(By.text("01-good.tsv")), 10000)!!.longClick()
            assertTrue("system picker must enable multiple selection", device.wait(Until.hasObject(By.text("1 selected")), 5000))
            tap("02-bad.tsv"); tap("03-good.dict.yaml")
            assertTrue(device.wait(Until.hasObject(By.text("3 selected")), 5000))
            val started = CountDownLatch(1)
            DictionaryFiles.executor.execute { started.countDown(); release.await(45, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            tap("Select")
            assertTrue(device.wait(Until.hasObject(By.text("导入词库文件").enabled(false)), 5000))
            device.setOrientationLeft()
            Thread.sleep(1000)
            if (!device.wait(Until.hasObject(By.text("导入词库文件")), 3000))
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("导入词库文件"))
            assertTrue(device.wait(Until.hasObject(By.text("导入词库文件").enabled(false)), 10000))
            release.countDown()
            device.setOrientationNatural()
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("批量导入完成：成功 2 个，失败 1 个")), 30000))
            val report = device.findObject(By.textStartsWith("批量导入完成：")).text
            for (name in listOf("01-good.tsv", "02-bad.tsv", "03-good.dict.yaml")) assertTrue(report.contains(name))
            assertTrue(report.contains("词库备份信息损坏"))
            assertEquals(2, (ids() - original).size)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-batch-results.png"))
            picker(); device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Downloads")), 5000))
            device.pressBack() // 子目录返回到下载根目录后，再退出文件选择器。
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("已取消文件选择")), 10000))
            assertEquals(2, (ids() - original).size)
            device.pressHome(); device.waitForIdle()
            context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
                .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
            device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
            device.executeShellCommand("input text qingxuexingqiong")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 青学星穹，.*")).enabled(true)), 15000))
            device.executeShellCommand("input keyevent 62")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("青学星穹")), 10000))
            device.executeShellCommand("input text nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
            Thread.sleep(1000)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-batch-gloss.png"))
            device.executeShellCommand("input keyevent 62")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("青学星穹你好")), 10000))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-batch-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "dictionary-batch-failure.xml"))
            throw error
        } finally {
            release.countDown()
            device.setOrientationNatural(); device.unfreezeRotation()
            Preferences.save(context, saved)
            val drained = CountDownLatch(1)
            DictionaryFiles.executor.execute { drained.countDown() }
            assertTrue(drained.await(30, TimeUnit.SECONDS))
            for (id in ids() - original) DictionaryFiles.manage(context, "remove", JSONObject().put("id", id))
            for (uri in fixtures) context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun duplicateUrisImportOnceAndOversizedSelectionDoesNotStart() {
        val original = ids()
        val source = File(context.cacheDir, "batch-unique.tsv")
        source.writeText("你好\tni hao\t9000\n")
        val uri = Uri.fromFile(source)
        try {
            assertTrue(DictionaryFiles.importSelected(context, listOf(uri, uri)).startsWith("已导入"))
            assertEquals(1, (ids() - original).size)
            val before = ids()
            val tooMany = listOf(uri) + (1..32).map { Uri.parse("content://unavailable.dictionary.test/$it") }
            var rejected = false
            try { DictionaryFiles.importSelected(context, tooMany) }
            catch (error: IllegalArgumentException) { rejected = error.message!!.contains("最多选择 32") }
            assertTrue("oversized batch must be rejected before any file is imported", rejected)
            assertEquals(before, ids())
            val failures = DictionaryFiles.importSelected(context, tooMany.drop(1).take(2))
            assertTrue(failures.startsWith("批量导入完成：成功 0 个，失败 2 个"))
            assertEquals(before, ids())
        } finally {
            for (id in ids() - original) DictionaryFiles.manage(context, "remove", JSONObject().put("id", id))
            source.delete()
        }
    }
}
