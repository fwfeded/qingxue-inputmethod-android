//! 真实页面验证下载直达与学习状态，保留既有模型和用户偏好。
package dev.qingstudy.ime

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PhoneFeedbackSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var saved: JSONObject
    @Before fun prepare() {
        saved = Preferences.read(context); Preferences.save(context, JSONObject())
        device.setOrientationNatural()
    }
    @After fun restore() {
        Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation()
    }
    private fun launch(page: String? = null) {
        context.startActivity(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .apply { if (page != null) putExtra("page", page) })
        device.waitForIdle()
    }
    private fun tap(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 1000))
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        device.waitForIdle()
        device.wait(Until.findObject(By.text(text)), 10000)!!.click()
    }
    @Test fun downloadIsDirectlyVisibleAndRotationKeepsModelPage() {
        val ready = VoiceModels.isReady(context)
        launch()
        assertNotNull("设置首页不搜索即可找到下载入口",
            device.wait(Until.findObject(By.text("离线语音包")), 10000))
        tap("离线语音包")
        assertNotNull(device.wait(Until.findObject(By.desc("离线包状态")), 10000))
        assertTrue(device.hasObject(By.text(if (ready) "删除离线语音包" else "立即下载")))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-model-entry.png"))
        device.setOrientationLeft()
        if (!device.wait(Until.hasObject(By.desc("离线包状态")), 2000))
            assertTrue("横屏内容仍应能滚动到模型状态", UiScrollable(UiSelector().scrollable(true))
                .scrollIntoView(UiSelector().description("离线包状态")))
        assertNotNull(device.wait(Until.findObject(By.desc("离线包状态")), 10000))
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("识别来源")), 10000))
        device.setOrientationNatural()
        assertNotNull(device.wait(Until.findObject(By.text("识别来源")), 10000))
        assertFalse(device.hasObject(By.desc("离线包状态")))
        assertEquals(ready, VoiceModels.isReady(context))
    }
    @Test fun learningReportsEffectiveStateAndPreservesChoiceAfterReopen() {
        launch("learning")
        assertFalse(Preferences.read(context).optBoolean("learning", false))
        assertTrue(device.wait(Until.hasObject(By.desc("输入习惯学习状态").textContains("未开启")), 10000))
        tap("本机学习、词汇记录与统计")
        assertTrue(device.wait(Until.hasObject(By.desc("输入习惯学习状态").textContains("正在学习")), 10000))
        assertTrue(Preferences.read(context).getBoolean("learning"))
        tap("学习输入习惯")
        assertTrue(device.wait(Until.hasObject(By.desc("输入习惯学习状态").textContains("已暂停")), 10000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-learning-state.png"))
        launch("learning")
        assertTrue(device.wait(Until.hasObject(By.desc("输入习惯学习状态").textContains("已暂停")), 10000))
        assertFalse(Preferences.read(context).getBoolean("habitLearning"))
        tap("本机学习、词汇记录与统计")
        assertTrue(device.wait(Until.hasObject(By.desc("输入习惯学习状态").textContains("未开启")), 10000))
    }
}
