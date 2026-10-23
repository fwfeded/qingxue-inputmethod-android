// 真正 IME 产生学习记录，经系统文档选择器导出，并核对私密输入和停用学习。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class LearningBackupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 3000)) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        Thread.sleep(1000) // 文件网格或页面滚动稳定后重新定位。
        device.findObject(UiSelector().text(text)).click()
    }
    private fun input(mode: String, pinyin: String, expected: String) {
        device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.PhoneFixtureActivity --es mode $mode -f 0x10008000")
        assertTrue(device.wait(Until.hasObject(By.text("手机输入验证 $mode")), 10000))
        device.findObject(UiSelector().description("手机测试输入框")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)), 30000))
        device.executeShellCommand("input text $pinyin")
        assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 $expected，.*")).enabled(true)), 15000))
        device.executeShellCommand("input keyevent 62")
        assertTrue(device.wait(Until.hasObject(By.desc("手机测试输入框").text(expected)), 10000))
    }
    private fun exportThroughPicker(): JSONObject {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("学习与隐私")), 10000)!!.click()
        tap("学习记录备份")
        tap("导出学习备份")
        assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000))
        device.findObject(UiSelector().description("Show roots")).click()
        device.waitForIdle()
        device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
        device.waitForIdle()
        device.wait(Until.findObject(By.res("android", "title").clazz("android.widget.EditText")), 10000)!!.text = "qstudy-learning-test.json"
        tap("SAVE")
        val complete = device.wait(Until.hasObject(By.desc("学习备份结果").textStartsWith("已导出学习备份")), 20000)
        if (!complete) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "learning-backup-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "learning-backup-failure.xml"))
        }
        assertTrue(device.findObject(UiSelector().description("学习备份结果")).text, complete)
        val statusBounds = device.findObject(UiSelector().description("学习备份结果")).visibleBounds
        assertTrue("成功提示应位于可视区域", statusBounds.bottom < device.displayHeight * 0.92)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "learning-backup-export.png"))
        // UiDevice 的 shell 输出按块解码会切断多字节字符；复制原始文件后整体按 UTF-8 读取。
        val exported = File(context.getExternalFilesDir(null), "learning-backup-test.json")
        device.executeShellCommand("cp /sdcard/Download/qstudy-learning-test.json ${exported.absolutePath}")
        return JSONObject(exported.readText(Charsets.UTF_8))
    }
    private fun chooseRestore() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("学习与隐私")), 10000)!!.click()
        tap("学习记录备份")
        tap("恢复学习备份")
        assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000))
        device.findObject(UiSelector().description("Show roots")).click()
        device.waitForIdle()
        device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
        device.waitForIdle()
        tap("qstudy-learning-test.json")
    }
    @Test fun savedLearningExportAndPrivacyBoundaries() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        try {
            Preferences.save(context, JSONObject().put("learning", true))
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            device.executeShellCommand("rm -f /sdcard/Download/qstudy-learning-test.json")
            var directory = LearningBackupFiles.directory(context)
            fun frequencyCount(): Long = File(directory, "frequency.tsv").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("开发\t") }?.substringAfter('\t')?.toLong() ?: 0L
            val countBefore = frequencyCount()
            input("text", "kaifa", "开发")
            assertEquals("真实输入必须新增学习记录", countBefore + 1, frequencyCount())
            val snapshot = exportThroughPicker()
            val summaryAtBackup = JSONObject(File(directory, "summary.json").readText())
            assertEquals("qingstudy-learning-backup", snapshot.getString("format"))
            assertEquals(1, snapshot.getInt("version"))
            val files = snapshot.getJSONObject("files")
            assertEquals(8, files.length())
            assertTrue(files.getString("frequency.tsv").contains("开发\t"))
            assertTrue(files.getString("vocabulary.tsv").contains("develop"))
            assertFalse(files.has("input-log.jsonl"))
            assertFalse(files.has("settings.json"))
            for (name in files.keys()) {
                val table = File(directory, name)
                if (files.isNull(name)) assertFalse(table.exists()) else assertEquals(name, table.readText(), files.getString(name))
            }
            // 备份后继续输入；取消恢复不能更改记录，确认后应回到备份中的计数。
            input("text", "kaifa", "开发")
            assertEquals(countBefore + 2, frequencyCount())
            chooseRestore()
            assertTrue(device.wait(Until.hasObject(By.text("确认恢复学习记录")), 20000))
            tap("取消")
            assertEquals(countBefore + 2, frequencyCount())
            val oldDirectory = directory
            val oldFrequency = File(oldDirectory, "frequency.tsv").readText()
            val handle = NativeBridge.create(File(context.createDeviceProtectedStorageContext().filesDir, "engine-v1").absolutePath)
            try {
                NativeBridge.step(handle, "configure", Preferences.engine(context, false))
                NativeBridge.step(handle, "input", "kaifa")
                chooseRestore()
                assertTrue(device.wait(Until.hasObject(By.text("确认恢复学习记录")), 20000))
                // 校验预览在横竖屏切换后仍能确认同一个本机副本。
                device.setOrientationLeft(); device.waitForIdle()
                device.setOrientationNatural(); device.waitForIdle()
                assertTrue(device.wait(Until.hasObject(By.text("确认恢复学习记录")), 10000))
                tap("替换并恢复")
                assertTrue(device.wait(Until.hasObject(By.desc("学习备份结果").textStartsWith("已恢复学习备份")), 20000))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "learning-backup-restore.png"))
                directory = LearningBackupFiles.directory(context)
                assertNotEquals(oldDirectory, directory)
                for (name in files.keys()) {
                    if (!files.isNull(name)) assertEquals(name, files.getString(name), File(directory, name).readText())
                }
                val restoredSummary = JSONObject(File(directory, "summary.json").readText())
                for (key in listOf("total", "english", "days", "seen", "committed", "used")) {
                    assertEquals(key, summaryAtBackup.getLong(key), restoredSummary.getLong(key))
                }
                NativeBridge.step(handle, "space", "")
            } finally { NativeBridge.close(handle) }
            assertEquals(oldFrequency, File(oldDirectory, "frequency.tsv").readText())
            assertEquals(countBefore + 1, frequencyCount())
            input("text", "kaifa", "开发")
            assertEquals("恢复后新输入应从恢复的计数继续", countBefore + 2, frequencyCount())
            // 损坏备份经系统选择器必须报错，不能发布新代。
            val bad = File(context.getExternalFilesDir(null), "invalid-learning.json")
            bad.writeText(snapshot.toString().replace("\"version\":1", "\"version\":999"))
            device.executeShellCommand("cp ${bad.absolutePath} /sdcard/Download/qstudy-learning-test.json")
            chooseRestore()
            assertTrue(device.wait(Until.hasObject(By.desc("学习备份结果").textStartsWith("操作失败")), 20000))
            assertEquals(directory, LearningBackupFiles.directory(context))
            assertEquals(countBefore + 2, frequencyCount())
            val before = File(directory, "frequency.tsv").readText()
            val usageBefore = File(directory, "usage.tsv").readText()
            input("private", "yingyu", "英语")
            assertEquals(before, File(directory, "frequency.tsv").readText())
            assertEquals(usageBefore, File(directory, "usage.tsv").readText())
            Preferences.save(context, JSONObject().put("learning", false))
            input("text", "nihao", "你好")
            assertEquals(before, File(directory, "frequency.tsv").readText())
            assertEquals(usageBefore, File(directory, "usage.tsv").readText())
            // 关闭学习仍能备份既有记录，且导出不产生新的学习计数。
            val destination = File(context.cacheDir, "learning-disabled-test.json")
            NativeBridge.exportLearning(File(context.filesDir, "learning").absolutePath, destination.absolutePath)
            val disabled = JSONObject(destination.readText()).getJSONObject("files")
            assertEquals(before, disabled.getString("frequency.tsv"))
            assertEquals(usageBefore, disabled.getString("usage.tsv"))
            destination.delete()
        } finally { Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation() }
    }
}
