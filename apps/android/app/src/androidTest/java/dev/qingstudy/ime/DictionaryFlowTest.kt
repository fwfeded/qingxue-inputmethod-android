// 系统文件选择器导入、导出与重新导入；输入框验证词库开关和持久化。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class DictionaryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 3000)) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        Thread.sleep(1000) // 等待文件网格或页面滚动回弹，点击时重新定位。
        device.findObject(UiSelector().text(text)).click()
    }
    private fun action(description: String) {
        if (!device.hasObject(By.desc(description))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(description))
        val bounds = device.findObject(UiSelector().description(description)).visibleBounds
        if (bounds.centerY() > device.displayHeight * 0.78) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 2, 20)
            device.waitForIdle()
        }
        device.findObject(UiSelector().description(description)).click()
    }
    private fun manager() {
        context.startActivity(Intent(context, DictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("导入词库文件").enabled(true)), 10000))
    }
    private fun downloadRoot() {
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "dictionary-before-root.xml"))
        assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000))
        device.findObject(UiSelector().description("Show roots")).click()
        device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.res("android", "title").text("Downloads")), 10000))
        device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
        device.waitForIdle()
    }
    private fun importFile(name: String) {
        tap("导入词库文件")
        downloadRoot()
        device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-picker.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "dictionary-picker.xml"))
        tap(name)
    }
    private fun result(prefix: String) {
        assertTrue(prefix, device.wait(Until.hasObject(By.textStartsWith(prefix)), 20000))
    }
    @Test fun safImportExportReimportAndInvalidFile() {
        val saved = Preferences.read(context)
        val originalIds = JSONArray(DictionaryFiles.manage(context, "list")).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet() }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            val fixture = File(context.getExternalFilesDir(null), "qstudy-roundtrip.tsv")
            val metadata = JSONObject().put("name", "qstudy-roundtrip").put("license", "MIT")
                .put("attribution", "青学测试").put("source", "https://example.org/terms")
                .put("version", "fixture-v1").put("generator", "test").put("entries", 1)
            // 模拟 0.3.0 已导出的旧备份，名称和许可须贯穿 TSV 与 QJ 往返。
            fixture.writeText("\uFEFF# $metadata\n青学星穹\tqing xue xing qiong\t900000\n")
            device.executeShellCommand("cp ${fixture.absolutePath} /sdcard/Download/qstudy-roundtrip.tsv")
            val invalid = File(context.getExternalFilesDir(null), "qstudy-invalid.tsv")
            invalid.writeText("\uFEFF# qingjian-dictionary-metadata-v1: {broken\n青学错误\tqing xue cuo wu\t900000\n")
            device.executeShellCommand("cp ${invalid.absolutePath} /sdcard/Download/qstudy-invalid.tsv")
            device.executeShellCommand("rm -f /sdcard/Download/qstudy-export.tsv /sdcard/Download/qstudy-export.qj")
            manager()
            importFile("qstudy-invalid.tsv")
            result("操作失败")
            importFile("qstudy-roundtrip.tsv")
            result("已导入")
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-import.png"))
            action("导出 TSV qstudy-roundtrip")
            downloadRoot()
            device.wait(Until.findObject(By.res("android", "title").clazz("android.widget.EditText")), 10000)!!.text = "qstudy-export.tsv"
            tap("SAVE")
            result("已导出")
            val exported = device.executeShellCommand("cat /sdcard/Download/qstudy-export.tsv")
            assertFalse(exported.contains("\uFEFF"))
            assertTrue(exported.contains("青学星穹\tqing xue xing qiong\t900000"))
            val exportedMetadata = JSONObject(exported.lineSequence().first().removePrefix("# qingjian-dictionary-metadata-v1: "))
            for (key in metadata.keys()) assertEquals(key, metadata.get(key), exportedMetadata.get(key))
            // 先移除原词库，再重新导入导出文件，避免原副本掩盖导出错误。
            action("移除 qstudy-roundtrip")
            tap("移除")
            result("已移除")
            importFile("qstudy-export.tsv")
            result("已导入")
            action("停用 qstudy-roundtrip")
            result("已停用")
            val disabledRows = JSONArray(DictionaryFiles.manage(context, "list"))
            val disabled = (0 until disabledRows.length()).map { disabledRows.getJSONObject(it) }.first { it.getString("name") == "qstudy-roundtrip" }
            assertFalse(disabled.getBoolean("enabled"))
            assertEquals("MIT", disabled.getString("license"))
            action("启用 qstudy-roundtrip")
            result("已启用")
            action("导出 QJ qstudy-roundtrip")
            downloadRoot()
            device.wait(Until.findObject(By.res("android", "title").clazz("android.widget.EditText")), 10000)!!.text = "qstudy-export.qj"
            tap("SAVE")
            result("已导出")
            action("移除 qstudy-roundtrip")
            tap("移除")
            result("已移除")
            importFile("qstudy-export.qj")
            result("已导入：qstudy-roundtrip")
            val restoredRows = JSONArray(DictionaryFiles.manage(context, "list"))
            val restored = (0 until restoredRows.length()).map { restoredRows.getJSONObject(it) }.first { it.getString("name") == "qstudy-roundtrip" }
            assertEquals("MIT", restored.getString("license"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-qj-roundtrip.png"))
            context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
                .putExtra("mode", "text")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
            device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
            device.executeShellCommand("input text qingxuexingqiong")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 青学星穹，.*")).enabled(true)), 15000))
            device.executeShellCommand("input keyevent 62")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("青学星穹")), 10000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-roundtrip-commit.png"))
        } finally {
            Preferences.save(context, saved)
            val rows = JSONArray(DictionaryFiles.manage(context, "list"))
            for (i in 0 until rows.length()) {
                val id = rows.getJSONObject(i).getString("id")
                if (id !in originalIds) DictionaryFiles.manage(context, "remove", JSONObject().put("id", id))
            }
        }
    }

    @Test fun bomPlainTsvAndRimeKeepFirstWordAndRejectUnsupportedHeaders() {
        val saved = Preferences.read(context)
        val originalIds = JSONArray(DictionaryFiles.manage(context, "list")).let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject())
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            val fixtures = listOf(
                "qstudy-bom.tsv" to "你好\tni hao\t900000\r\n",
                "qstudy-bom.dict.yaml" to "---\r\nname: qstudy-bom-rime\r\n...\r\n你好\tni hao\t900000\r\n",
                "qstudy-bom-columns.dict.yaml" to "---\nname: unsupported\ncolumns: [text, code, weight]\n...\n你好\tni hao\t9000\n"
            )
            for ((name, body) in fixtures) {
                val file = File(context.getExternalFilesDir(null), name)
                file.writeText("\uFEFF$body")
                device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
            }
            manager()
            for ((index, fixture) in fixtures.take(2).withIndex()) {
                importFile(fixture.first)
                val name = if (index == 0) "qstudy-bom" else "qstudy-bom-rime"
                result("已导入：$name，1 条")
                val rows = JSONArray(DictionaryFiles.manage(context, "list"))
                val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == name }
                val exported = File(context.cacheDir, "bom-checked.tsv")
                try {
                    DictionaryFiles.manage(context, "export", JSONObject().put("id", row.getString("id"))
                        .put("format", "tsv").put("path", exported.absolutePath))
                    assertEquals("你好\tni hao\t900000", exported.readLines().last())
                    assertFalse(exported.readText().contains("\uFEFF"))
                } finally { exported.delete() }
            }
            val before = DictionaryFiles.manage(context, "list")
            importFile(fixtures.last().first)
            result("操作失败")
            assertTrue(device.wait(Until.hasObject(By.textContains("import_tables 或 columns")), 5000))
            assertEquals(before, DictionaryFiles.manage(context, "list"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-bom-rejected.png"))
            device.pressHome()
            device.waitForIdle()
            context.startActivity(Intent().setComponent(ComponentName("dev.qingstudy.ime.test", "dev.qingstudy.ime.PhoneFixtureActivity"))
                .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 text")), 10000))
            device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
            device.executeShellCommand("input text nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)), 15000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "dictionary-bom-gloss.png"))
            device.executeShellCommand("input keyevent 62")
            assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text("你好")), 10000))
        } finally {
            Preferences.save(context, saved)
            val rows = JSONArray(DictionaryFiles.manage(context, "list"))
            for (i in 0 until rows.length()) {
                val id = rows.getJSONObject(i).getString("id")
                if (id !in originalIds) DictionaryFiles.manage(context, "remove", JSONObject().put("id", id))
            }
        }
    }
}
