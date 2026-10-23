//! 已批准界面的真机控件流程：音色保存、固定预览、真实整理核对采用与撤回。
package dev.qingstudy.ime

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class FeedbackTextUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private lateinit var saved: JSONObject
    @Before fun setup() { saved = Preferences.read(context); device.setOrientationNatural() }
    @After fun restore() { Preferences.save(context, saved); device.unfreezeRotation() }
    private fun open(type: Class<out Activity>, source: String = "") {
        inst.startActivitySync(Intent(context, type).putExtra("source", source).putExtra("appearance_tab", "feedback")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync(); device.waitForIdle()
    }
    private fun reveal(desc: String): UiObject2 {
        if (device.findObject(By.desc(desc))?.visibleBounds?.isEmpty != false) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(desc))
        }
        device.waitForIdle()
        var previous = android.graphics.Rect(); var stable = 0
        repeat(40) {
            val view = device.findObject(By.desc(desc)); val rect = view?.visibleBounds ?: android.graphics.Rect()
            stable = if (!rect.isEmpty && rect == previous) stable + 1 else 0
            if (stable >= 5) return view!!
            previous = rect; SystemClock.sleep(100)
        }
        error("控件未稳定：$desc")
    }
    @Test fun allTonesSaveReopenAndStayDisabledWhenSoundIsOff() {
        FeedbackSettings.save(context, FeedbackSettings(sound = true, volume = 90))
        open(AppearanceActivity::class.java)
        for ((tone, title) in FeedbackSettings.tones.filterKeys { it != "system" } + mapOf("system" to "系统键音")) {
            reveal("按键音色").click()
            device.wait(Until.findObject(By.text(title)), 3000)!!.click(); device.waitForIdle()
            assertEquals(tone, FeedbackSettings.read(context).tone)
            for (kind in listOf("letter", "delete", "action")) reveal("反馈试按 $kind").click()
        }
        open(AppearanceActivity::class.java)
        assertEquals("system", FeedbackSettings.read(context).tone)
        reveal("按键音色")
        device.takeScreenshot(File(context.getExternalFilesDir(null), "text-feedback-tones.png"))
        FeedbackSettings.save(context, FeedbackSettings(sound = false, tone = "low"))
        open(AppearanceActivity::class.java)
        assertFalse(reveal("按键音色").isEnabled)
        assertFalse(reveal("按键音量").isEnabled)
    }
    @Test fun realResultIsComparedBeforeAdoptionAndUndoRestoresWholeDraft() {
        assertTrue(TextModels.ready(context))
        val source = "这笔钱不是八千是一万八千元不要今天转账等合同签完再转"
        open(TextToolsActivity::class.java, source)
        assertFalse(reveal("采用结果").isEnabled)
        reveal("开始整理").click()
        val deadline = SystemClock.uptimeMillis() + 120000
        while (SystemClock.uptimeMillis() < deadline && !device.hasObject(By.desc("采用结果").enabled(true))) {
            reveal("采用结果")
            if (device.hasObject(By.desc("采用结果").enabled(true))) break
            SystemClock.sleep(500)
        }
        assertTrue("模型输出必须通过原文保护后才能采用", reveal("采用结果").isEnabled)
        val result = reveal("整理结果").text
        assertEquals(source, reveal("整理原文").text)
        reveal("采用结果").click()
        assertEquals(result, reveal("整理原文").text)
        reveal("撤回采用").click()
        assertEquals(source, reveal("整理原文").text)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "text-review-undo.png"))
        reveal("管理离线文字包").click()
        assertTrue(device.wait(Until.hasObject(By.desc("文字包状态").textContains("已就绪")), 5000))
        device.pressBack(); assertEquals(source, reveal("整理原文").text)
    }
    @Test fun editingOrLeavingDuringGenerationKeepsTheDraft() {
        assertTrue(TextModels.ready(context))
        open(TextToolsActivity::class.java, "明天不要上线".repeat(40))
        reveal("开始整理").click()
        reveal("取消整理").click()
        assertFalse(reveal("采用结果").isEnabled)
        reveal("整理原文").text = "新的原文不应被旧结果替换"
        SystemClock.sleep(1500)
        assertEquals("新的原文不应被旧结果替换", reveal("整理原文").text)
        assertFalse(reveal("采用结果").isEnabled)
    }
}
