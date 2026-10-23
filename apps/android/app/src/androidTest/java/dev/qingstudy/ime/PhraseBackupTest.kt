// 真实文件选择器往返及导入后的 IME 原文提交；错误和过期预览不得覆盖规则。
package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class PhraseBackupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 3000)) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        device.wait(Until.findObject(By.text(text).enabled(true)), 10000)!!.click()
    }
    private fun result(prefix: String) {
        assertTrue(prefix, device.wait(Until.hasObject(By.desc("短语备份结果").textStartsWith(prefix)), 20000))
    }
    private fun manager() {
        context.startActivity(Intent(context, PhraseBackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("导入短语备份").enabled(true)), 10000))
    }
    private fun downloads() {
        assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000))
        device.findObject(UiSelector().description("Show roots")).click()
        device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.res("android:id/title").text("Downloads")), 10000))
        device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
        device.waitForIdle()
    }
    private fun choose(name: String = "qstudy-phrases-test.json") {
        tap("导入短语备份"); downloads(); tap(name)
    }
    private fun rows(): JSONArray = Preferences.read(context).optJSONArray("customPhrases") ?: JSONArray()
    private fun preview() { assertTrue(device.wait(Until.hasObject(By.text("确认替换短语")), 20000)) }
    @Test fun safRoundtripCancelRotationStaleAndInvalidFiles() {
        val saved = Preferences.read(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val text = "  备份原文😀\n第二行\tEND  "
        val original = JSONArray().put(JSONObject().put("code","nihao").put("position",2).put("text",text).put("enabled",true))
            .put(JSONObject().put("code","qqq").put("position",9).put("text","停用内容").put("enabled",false))
        try {
            Preferences.save(context, JSONObject().put("customPhrases",original))
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            device.executeShellCommand("rm -f /sdcard/Download/qstudy-phrases-test.json")
            val bad = File(context.getExternalFilesDir(null), "qstudy-phrases-invalid.json")
            bad.writeText("{\"format\":\"qingstudy-phrases\",\"version\":99,\"phrases\":[]}")
            device.executeShellCommand("cp ${bad.absolutePath} /sdcard/Download/qstudy-phrases-invalid.json")
            context.startActivity(Intent(context, PhraseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            tap("短语导入导出"); tap("导出短语备份"); downloads()
            device.wait(Until.findObject(By.res("android:id/title").clazz("android.widget.EditText")), 10000)!!.text = "qstudy-phrases-test.json"
            tap("SAVE"); result("已导出 2 条")
            val exported = File(context.getExternalFilesDir(null), "phrase-export-check.json")
            device.executeShellCommand("cp /sdcard/Download/qstudy-phrases-test.json ${exported.absolutePath}")
            val archive = JSONObject(exported.readText(Charsets.UTF_8))
            assertEquals("qingstudy-phrases",archive.getString("format"))
            assertEquals(1,archive.getInt("version"))
            val normalized = JSONObject(NativeBridge.phrases(original.toString())).getJSONArray("phrases").toString()
            assertEquals(normalized,JSONObject(NativeBridge.phraseFile("inspect",exported.readText())).getJSONArray("phrases").toString())
            Preferences.save(context, JSONObject().put("customPhrases",JSONArray()))
            choose(); preview(); tap("取消"); result("已取消导入"); assertEquals(0,rows().length())
            choose(); preview()
            device.setOrientationLeft(); device.waitForIdle(); device.setOrientationNatural(); device.waitForIdle(); preview()
            Preferences.save(context,Preferences.read(context).put("transferSentinel","保留其他设置"))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"phrase-file-preview.png"))
            tap("替换全部短语"); result("已导入 2 条")
            assertEquals(normalized, rows().toString())
            assertEquals("保留其他设置", Preferences.read(context).getString("transferSentinel"))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"phrase-file-restored.png"))
            // 重建服务后确认读取导入规则，普通中文释义仍在。
            device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
            device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
            device.executeShellCommand("am start -W -n dev.qingstudy.ime.test/dev.qingstudy.ime.EditorFixtureActivity -f 0x10008000")
            device.wait(Until.findObject(By.desc("外部文本框一")),10000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("按键 回车").enabled(true)),30000))
            device.executeShellCommand("input text nihao")
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 你好，.*hello.*")).enabled(true)),15000))
            assertTrue(device.wait(Until.hasObject(By.desc(Pattern.compile("候选 .*备份原文.*")).enabled(true)),10000))
            device.executeShellCommand("input keyevent 9")
            assertTrue(device.wait(Until.hasObject(By.desc("外部文本框一").text(text)),10000))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"phrase-file-commit.png"))
            manager(); choose(); preview()
            val changed = rows().put(JSONObject().put("code","abc").put("position",1).put("text","新规则").put("enabled",true))
            Preferences.save(context, Preferences.read(context).put("customPhrases",changed))
            tap("替换全部短语"); result("操作失败：短语已变化")
            assertEquals(changed.toString(),rows().toString())
            choose("qstudy-phrases-invalid.json"); result("操作失败：不是受支持的青学短语备份 v1")
            assertEquals(changed.toString(),rows().toString())
            val malformed = File(context.cacheDir,"phrase-malformed-test.json")
            try {
                malformed.writeBytes(byteArrayOf(0xc3.toByte(),0x28))
                val malformedError = runCatching { PhraseBackupFiles.inspect(context,Uri.fromFile(malformed)) }.exceptionOrNull()
                assertTrue(malformedError?.message?.contains("UTF-8") == true)
                malformed.writeBytes(ByteArray(2*1024*1024+1){32})
                val sizeError = runCatching { PhraseBackupFiles.inspect(context,Uri.fromFile(malformed)) }.exceptionOrNull()
                assertTrue(sizeError?.message?.contains("2 MiB") == true)
            } finally { malformed.delete() }
            assertEquals(changed.toString(),rows().toString())
            device.takeScreenshot(File(context.getExternalFilesDir(null),"phrase-file-invalid.png"))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null),"phrase-file-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"phrase-file-failure.xml"))
            throw error
        } finally { device.setOrientationNatural(); device.unfreezeRotation(); Preferences.save(context,saved) }
    }
}
