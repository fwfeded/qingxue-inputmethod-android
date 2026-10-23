// 真实 IME/设置交互与 JNI 持久化；测试前后保留设备原设置与学习记录。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

class PersonalLearningFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val host get() = By.res("dev.qingstudy.ime", "test_input")
    private lateinit var saved: JSONObject
    private lateinit var voice: String
    private lateinit var backup: File
    @get:Rule val failures = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) { shot("failure-${description.methodName}") }
    }
    private fun key(description: String) = device.wait(Until.findObject(By.desc(description).enabled(true)), 20000) ?: error("缺少 $description")
    private fun stepResult(raw: String): JSONObject = JSONObject(raw).also { assertFalse(raw, it.has("error")) }
    @Before fun setup() {
        saved = Preferences.read(context)
        voice = VoiceSettings.read(context).toString()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        backup = File(context.cacheDir, "personal-before-${UUID.randomUUID()}.json")
        stepResult(NativeBridge.exportLearning(File(context.filesDir, "learning").absolutePath, backup.absolutePath))
        Preferences.save(context, JSONObject().put("learning", true).put("habitLearning", true))
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        device.setOrientationNatural()
    }
    @After fun cleanup() {
        device.pressHome()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        if (::backup.isInitialized && backup.exists()) {
            stepResult(NativeBridge.learning(File(context.filesDir, "learning").absolutePath, "restore", backup.absolutePath))
            backup.delete()
        }
        Preferences.save(context, saved)
        File(context.filesDir, "voice-settings.json").writeText(voice)
        File(context.filesDir, "debug-voice-enabled").delete()
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        device.unfreezeRotation()
    }
    private fun start() {
        device.pressHome()
        // HOME 转场尚未结束时启动 Activity，延迟的 HOME 会把新页面再次覆盖。
        assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 10000))
        device.waitForIdle()
        context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val input = device.wait(Until.findObject(host), 15000)
        if (input == null) shot("start-missing-input")
        requireNotNull(input) { "试打页未进入前台" }.click()
        key("按键 空格")
    }
    private fun candidate(word: String) = By.desc(Pattern.compile("候选 ${Pattern.quote(word)}，.*")).enabled(true)
    private fun choose(pinyin: String, word: String) {
        device.executeShellCommand("input text $pinyin")
        val item = device.wait(Until.findObject(candidate(word)), 15000) ?: error("缺少候选 $word")
        item.click()
        assertTrue(device.wait(Until.hasObject(host.textEndsWith(word)), 10000))
    }
    private fun shot(name: String) {
        device.takeScreenshot(File(context.getExternalFilesDir(null), "personal-$name.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "personal-$name.xml"))
    }
    @Test fun continuationGlossTypingAndCursorBoundary() {
        start()
        choose("mingtian", "明天"); choose("jian", "见")
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        choose("mingtian", "明天")
        assertNotNull(device.wait(Until.findObject(candidate("见")), 10000))
        assertTrue(device.hasObject(By.desc("续词 · 本机学习")))
        shot("continuation")
        device.findObject(candidate("见")).click()
        assertTrue(device.wait(Until.hasObject(host.textEndsWith("明天见")), 10000))
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        choose("mingtian", "明天")
        device.executeShellCommand("input text nihao")
        assertNotNull(device.wait(Until.findObject(By.desc(Pattern.compile("候选 你好，.*hello.*"))), 10000))
        assertFalse(device.hasObject(By.desc("续词 · 本机学习")))
        device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
        choose("mingtian", "明天")
        assertTrue(device.wait(Until.hasObject(By.desc("续词 · 本机学习")), 10000))
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(device.wait(Until.gone(By.desc("续词 · 本机学习")), 10000))
        assertFalse(device.hasObject(candidate("见")))
    }
    @Test fun blockUndoRestartAndSettingsRestore() {
        start()
        device.executeShellCommand("input text gongyuan")
        val word = "公园"
        device.wait(Until.findObject(candidate(word)), 15000)!!.longClick()
        key("工具 block").click()
        assertTrue(device.wait(Until.hasObject(By.text("撤销屏蔽")), 10000))
        assertFalse(device.hasObject(candidate(word)))
        assertTrue(Preferences.read(context).getJSONArray("blockedWords").toString().contains(word))
        shot("blocked")
        key("工具 unblock").click()
        assertNotNull(device.wait(Until.findObject(candidate(word)), 10000))
        key("按键 空格")
        device.findObject(candidate(word)).longClick()
        key("工具 block").click()
        assertTrue(device.wait(Until.hasObject(By.text("撤销屏蔽")), 10000))
        key("工具 close").click()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        start()
        device.executeShellCommand("input text gongyuan")
        assertTrue(device.wait(Until.hasObject(candidate("公元")), 15000))
        assertFalse(device.hasObject(candidate(word)))
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("page", "blocked").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text(word)), 10000))
        shot("settings-restore")
        device.findObject(By.text(word)).click()
        assertTrue(device.wait(Until.hasObject(By.text("暂无屏蔽的候选")), 10000))
        start()
        device.executeShellCommand("input text gongyuan")
        assertNotNull(device.wait(Until.findObject(candidate(word)), 15000))
    }
    private fun weight(word: String): Long {
        val file = File(LearningBackupFiles.directory(context), "frequency.tsv")
        return if (!file.exists()) 0 else file.readLines().firstOrNull { it.startsWith("$word\t") }?.substringAfter('\t')?.toLong() ?: 0
    }
    @Test fun settingsGatesAndClearKeepEnglishVocabulary() {
        start(); choose("nihao", "你好")
        val directory = LearningBackupFiles.directory(context)
        val vocabulary = File(directory, "vocabulary.tsv").readText()
        assertTrue(vocabulary.isNotBlank())
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("page", "learning").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("学习输入习惯")), 10000))
        device.findObject(By.text("学习输入习惯")).click()
        assertTrue(device.wait(Until.hasObject(By.text("学习输入习惯").checked(false)), 5000))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("学习语音手动纠正"))
        assertTrue(device.hasObject(By.text("上屏后推荐续词").enabled(false)))
        assertTrue(device.hasObject(By.text("学习语音手动纠正").enabled(false)))
        shot("learning-settings")
        assertFalse(Preferences.read(context).getBoolean("habitLearning"))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("清除当前输入习惯"))
        device.findObject(By.text("清除当前输入习惯")).click()
        device.wait(Until.findObject(By.text("清除").clazz("android.widget.Button")), 5000)!!.click()
        assertNotNull(device.wait(Until.findObject(By.text("当前输入习惯已清除，返回输入后生效。")), 15000))
        val next = LearningBackupFiles.directory(context)
        assertNotEquals(directory, next)
        assertEquals(vocabulary, File(next, "vocabulary.tsv").readText())
        assertFalse(File(next, "frequency.tsv").exists())
    }
    @Test fun candidateManagementRemainsReachableInPortraitAndLandscape() {
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            start()
            device.executeShellCommand("input text nihao")
            device.wait(Until.findObject(candidate("你好")), 15000)!!.longClick()
            val viewport = key("工具 close").parent.parent.parent.visibleBounds
            // 候选详情固定占用原键区；横屏可滚动到下方管理操作。
            if (!device.hasObject(By.desc("工具 block").enabled(true))) {
                device.swipe(viewport.centerX(), viewport.bottom - 12,
                    viewport.centerX(), viewport.top + 12, 25)
            }
            val block = key("工具 block")
            val bounds = block.visibleBounds
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
            assertTrue(bounds.bottom <= device.displayHeight && bounds.right <= device.displayWidth)
            shot(if (landscape) "landscape-details" else "portrait-details")
            block.click()
            assertTrue(device.wait(Until.hasObject(By.text("撤销屏蔽")), 10000))
            key("工具 unblock").click()
            assertNotNull(device.wait(Until.findObject(candidate("你好")), 10000))
            key("按键 空格")
        }
    }
    @Test fun manualVoiceConfirmationLearnsButKeyboardExitDoesNot() {
        assertTrue(File(context.filesDir, "debug-voice-input.wav").isFile)
        File(context.filesDir, "debug-voice-enabled").writeText("explicit test")
        VoiceSettings.save(context, "provider", "offline")
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        start()
        key("工具 voice").click()
        assertTrue(device.wait(Until.hasObject(host.textContains("天气")), 40000))
        if (key("语音 pause").text == "暂停") key("语音 pause").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续")), 20000))
        val recognized = key("语音编辑文字").text
        assertTrue(recognized.contains("天气"))
        val before = weight("天才")
        key("语音编辑文字").text = recognized.replaceFirst("天气", "天才")
        assertTrue(device.wait(Until.hasObject(host.textContains("天才")), 10000))
        key("语音 finish").click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 10000))
        val deadline = SystemClock.uptimeMillis() + 10000
        while (weight("天才") == before && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals(before + 1, weight("天才"))
        shot("voice-confirmed")
        // 第二次人工修改只切回键盘，不能以切换界面冒充最终确认。
        key("工具 voice").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音编辑文字").textContains("天气")), 40000))
        if (key("语音 pause").text == "暂停") key("语音 pause").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续")), 20000))
        val next = key("语音编辑文字").text
        key("语音编辑文字").text = next.replaceFirst("天气", "天才")
        device.findObjects(By.desc("语音 keyboard")).last().click()
        key("按键 空格")
        SystemClock.sleep(400)
        assertEquals(before + 1, weight("天才"))
    }
}
