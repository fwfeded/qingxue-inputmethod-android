package dev.qingstudy.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val host get() = By.res("dev.qingstudy.ime", "test_input")
    private lateinit var settings: String
    @Before fun setup() {
        settings = VoiceSettings.read(context).toString()
        File(context.filesDir, "voice-settings.json").writeText("{\"provider\":\"offline\"}")
        assertTrue("测试必须提供真实 PCM16/16000 WAV", File(context.filesDir, "debug-voice-input.wav").isFile)
        File(context.filesDir, "debug-voice-enabled").writeText("explicit test")
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        device.executeShellCommand("ime enable dev.qingstudy.ime/.PrototypeIme")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        device.setOrientationNatural()
    }
    @After fun cleanup() {
        device.pressBack()
        File(context.filesDir, "debug-voice-enabled").delete()
        File(context.filesDir, "voice-settings.json").writeText(settings)
        device.unfreezeRotation()
    }
    private fun key(description: String) = device.wait(Until.findObject(By.desc(description).enabled(true)), 20000) ?: error("Missing $description")
    private fun start(prefix: String = "") {
        context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val editor = device.wait(Until.findObject(host), 15000)!!
        editor.click(); assertNotNull(key("按键 空格"))
        if (prefix.isNotEmpty()) { editor.text = prefix; device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END); device.waitForIdle() }
    }
    private fun waitText(prefix: String = "") {
        val deadline = SystemClock.uptimeMillis() + 35000
        while (SystemClock.uptimeMillis() < deadline) {
            val text = device.findObject(host)?.text.orEmpty()
            if (text != "点击这里开始输入" && text.length > prefix.length) return
            SystemClock.sleep(150)
        }
        fail("真实音频未写入宿主：${device.findObject(By.desc("语音状态"))?.text}")
    }
    private fun hold(action: String, prefix: String = "") {
        val space = key("按键 空格").visibleBounds
        val down = SystemClock.uptimeMillis()
        var x = space.centerX().toFloat(); var y = space.centerY().toFloat()
        fun event(kind: Int) { MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN; inst.uiAutomation.injectInputEvent(it, true); it.recycle() } }
        event(MotionEvent.ACTION_DOWN)
        try {
            assertNotNull(key("语音 cancel"))
            waitText(prefix)
            shot("hold")
            if (action != "finish") { val target = key("语音 $action").visibleBounds; x = target.centerX().toFloat(); y = target.centerY().toFloat(); event(MotionEvent.ACTION_MOVE); SystemClock.sleep(100) }
        } finally { event(MotionEvent.ACTION_UP) }
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 15000))
    }
    private fun shot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "voice-$name.png")); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "voice-$name.xml")) }
    @Test fun leftUndoPreservesExistingTextAndLateResultCannotReturn() {
        start("保留原文"); hold("cancel", "保留原文")
        assertEquals("保留原文", device.findObject(host).text)
        SystemClock.sleep(1000); assertEquals("保留原文", device.findObject(host).text)
    }
    @Test fun releaseKeepsDictationAndShortSpaceStillSelectsChinese() {
        start(); hold("finish")
        val text = device.findObject(host).text
        assertTrue(text.isNotBlank()); assertFalse(text.endsWith("\n"))
        "nihao".forEach { key("按键 $it").click() }
        assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*"))), 15000))
        key("按键 空格").click()
        assertEquals(text + "你好", device.findObject(host).text)
        shot("gloss")
    }
    @Test fun rightReleasePerformsNewlineOnlyOnce() {
        start("原文"); hold("send", "原文")
        val text = device.findObject(host).text
        assertTrue("右松手应执行换行：$text", text.endsWith("\n"))
        assertEquals(1, text.count { it == '\n' })
        SystemClock.sleep(500); assertEquals(text, device.findObject(host).text)
    }
    @Test fun editorAllowsEditUndoPauseContinueAndKeyboardReturn() {
        start(); key("工具 voice").click(); waitText()
        // Pause waits for an actual final decoder result before editing.
        key("语音 pause").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续")), 15000))
        shot("editor")
        key("语音编辑文字").text = "语音编辑测试"
        assertTrue(device.wait(Until.hasObject(host.text("语音编辑测试")), 5000))
        key("语音 undo").click()
        assertFalse(device.findObject(host).text == "语音编辑测试")
        key("语音 pause").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("暂停")), 10000))
        val keyboardButtons = device.findObjects(By.desc("语音 keyboard")); keyboardButtons.last().click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 15000))
        assertNotNull(key("按键 空格"))
    }
    @Test fun focusChangeCancelsOldSessionAndPasswordCannotStartVoice() {
        start("已有文字"); key("工具 voice").click()
        val fixture = Intent().setClassName(inst.context.packageName, "dev.qingstudy.ime.PhoneFixtureActivity").putExtra("mode", "password").putExtra("hideHint", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        inst.context.startActivity(fixture)
        val editor = device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!; editor.click()
        assertNotNull(key("按键 空格")); SystemClock.sleep(1500)
        assertEquals("", editor.text)
        key("工具 voice").click(); assertFalse(device.hasObject(By.desc("语音状态")))
    }
    @Test fun microphonePathWithoutFixtureReportsStateAndCanCancel() {
        File(context.filesDir, "debug-voice-enabled").delete()
        start(); key("工具 voice").click()
        val deadline = SystemClock.uptimeMillis() + 25000
        var listening = false
        while (SystemClock.uptimeMillis() < deadline) {
            val value = device.findObject(By.desc("语音状态"))?.text.orEmpty()
            if (value.contains("聆听")) { listening = true; break }
            if (value.contains("失败") || value.contains("权限") || value.contains("占用")) fail(value)
            SystemClock.sleep(150)
        }
        assertTrue("AudioRecord 应实际进入聆听状态", listening)
        shot("microphone"); key("语音 undo").click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 5000))
    }
    @Test fun settingsAndNativeCommandsPersistAndRemainOffline() {
        VoiceSettings.save(context, "send_command", true)
        assertEquals("明天见" to true, VoiceSettings.format(context, "明天见，帮我发送。", true))
        assertEquals(false, VoiceSettings.format(context, "明天见，帮我发送。", false).second)
        VoiceSettings.save(context, "punctuation", "end")
        assertEquals("你好", VoiceSettings.format(context, "你好。", true).first)
        assertEquals("end", VoiceSettings.read(context).getString("punctuation"))
        context.startActivity(Intent(context, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("智能文字整理")), 10000))
        assertFalse(device.findObject(By.text("智能文字整理")).isEnabled)
        shot("settings")
        assertEquals("offline", VoiceSettings.read(context).optString("provider"))
        assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertTrue(VoiceModels.isReady(context))
    }
    @Test fun movingHostCursorBeforeFirstResultCancelsRecording() {
        start("保留原文"); key("工具 voice").click()
        assertNotNull(key("语音 pause"))
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_HOME)
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 10000))
        SystemClock.sleep(1500); assertEquals("保留原文", device.findObject(host).text)
    }
    @Test fun releasingResourcesDuringRecordingRemainsEditable() {
        start(); key("工具 voice").click(); waitText()
        inst.runOnMainSync { VoiceClient(context, EmptyVoiceListener()).release() }
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续").enabled(true)), 10000))
        device.findObjects(By.desc("语音 keyboard")).last().click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 10000))
        assertTrue(device.findObject(host).text.isNotBlank())
    }
    @Test fun selectedWordShowsRealDictionaryAlternatives() {
        start(); key("工具 voice").click(); waitText()
        key("语音 pause").click()
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续").enabled(true)), 15000))
        key("语音编辑文字").text = "公园"
        // Long-press the visible word, not the center of the mostly empty editor.
        val bounds = key("语音编辑文字").visibleBounds
        val density = context.resources.displayMetrics.density
        val x = bounds.left + (40 * density).toInt()
        val y = bounds.top + (28 * density).toInt()
        device.swipe(x, y, x, y, 150)
        shot("word-selected")
        assertNotNull(key("语音候选 公元"))
        shot("alternatives")
        key("语音候选 公元").click()
        assertTrue(device.wait(Until.hasObject(host.text("公元")), 5000))
        device.findObjects(By.desc("语音 keyboard")).last().click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 10000))
    }
    @Test fun editorSendUsesHostActionInsteadOfInsertingNewline() {
        val fixture = Intent().setClassName(inst.context.packageName, "dev.qingstudy.ime.PhoneFixtureActivity")
            .putExtra("mode", "send").putExtra("hideHint", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        inst.context.startActivity(fixture)
        val editor = device.wait(Until.findObject(By.desc("手机测试输入框")), 10000)!!
        editor.click()
        assertNotNull(key("按键 空格"))
        device.waitForIdle()
        key("工具 voice").click()
        assertNotNull(key("语音 pause"))
        assertTrue(device.wait(Until.hasObject(By.desc("语音 pause").text("继续").enabled(true)), 35000))
        assertEquals("发送", key("语音 send").text)
        key("语音 send").click()
        assertTrue(device.wait(Until.hasObject(By.text("已触发动作 4")), 5000))
        val finalText = device.findObject(By.desc("手机测试输入框")).text
        assertTrue(finalText.contains("天气")); assertFalse(finalText.contains("\n"))
    }
    @Test fun voiceKeepsKeyboardWindowAndBottomControlsReachable() {
        fun windowBounds(): android.graphics.Rect {
            val window = inst.uiAutomation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            return android.graphics.Rect().also { window.getBoundsInScreen(it) }
        }
        start(); val before = windowBounds()
        val keyBottom = key("按键 空格").visibleBounds.bottom
        key("工具 voice").click(); waitText()
        assertEquals("语音层不能抬高宿主输入框", before, windowBounds())
        val buttons = listOf("undo", "pause", "delete", "send").map { key("语音 $it").visibleBounds }
        for (bounds in buttons) {
            assertTrue("语音按钮不能进入底部导航区", bounds.bottom <= keyBottom + 8 * context.resources.displayMetrics.density)
            assertTrue(before.contains(bounds)); assertTrue(bounds.width() > 0 && bounds.height() > 0)
        }
        shot("safe-area")
        device.findObjects(By.desc("语音 keyboard")).last().click()
        assertTrue(device.wait(Until.gone(By.desc("语音状态")), 15000))
        assertEquals(before, windowBounds())
    }
    @Test fun settingsControlsPersistAndModelPageReflectsInstalledModel() {
        fun saved(key: String, value: Any) {
            val until = SystemClock.uptimeMillis() + 5000
            while (VoiceSettings.read(context).opt(key) != value && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            if (VoiceSettings.read(context).opt(key) != value) shot("settings-failure")
            assertEquals(value, VoiceSettings.read(context).opt(key))
        }
        context.startActivity(Intent(context, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("分段后空行")), 10000))
        device.waitForIdle()
        device.findObject(By.text("分段后空行")).click()
        saved("blank_lines", true)
        device.findObject(By.text("标点符号")).click()
        device.wait(Until.findObject(By.text("句末不加标点")), 5000)!!.click()
        saved("punctuation", "end")
        fun scrollTo(text: String) {
            assertTrue(androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                .scrollIntoView(androidx.test.uiautomator.UiSelector().text(text)))
            // The node can enter the viewport before the ScrollView finishes its fling.
            device.waitForIdle(); SystemClock.sleep(600); device.waitForIdle()
        }
        scrollTo("候选栏显示语音键"); device.findObject(By.text("候选栏显示语音键")).click()
        saved("entry", false)
        scrollTo("使用发送口令"); device.findObject(By.text("使用发送口令")).click()
        saved("send_command", true)
        scrollTo("设置发送口令"); device.findObject(By.text("设置发送口令")).click()
        device.wait(Until.findObject(By.text("立即发送")), 5000)!!.click()
        device.findObject(By.text("保存")).click()
        assertTrue(device.wait(Until.gone(By.text("保存")), 5000))
        assertTrue(VoiceSettings.read(context).getJSONArray("commands").toString().contains("立即发送"))
        scrollTo("离线语音包"); device.findObject(By.text("离线语音包")).click()
        assertNotNull(device.wait(Until.findObject(By.text("删除离线语音包")), 5000))
        assertNotNull(device.findObject(By.text("SenseVoice int8 + Silero VAD")))
        shot("model-settings")
        start(); assertFalse(device.hasObject(By.desc("工具 voice")))
        hold("finish")
    }
}
