// 分类设置、搜索与独立外观重置的真实 Activity 验证。
package dev.qingstudy.ime

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var saved: JSONObject
    @Before fun prepare() { saved = Preferences.read(context); Preferences.save(context, JSONObject()); device.setOrientationNatural() }
    @After fun restore() { Preferences.save(context, saved); device.setOrientationNatural(); device.unfreezeRotation() }
    private fun reveal(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 2000)) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
            android.os.SystemClock.sleep(500)
        }
        assertTrue(text, device.hasObject(By.text(text)))
    }
    private fun tap(text: String) {
        reveal(text)
        device.waitForIdle()
        // 搜索结果会同时收起 IME；节点出现早于窗口动画结束，需重新获取点击位置。
        android.os.SystemClock.sleep(500)
        device.findObject(By.text(text)).click()
    }
    @Test fun approvedDefaultsAndCustomValues() {
        assertEquals("用户确认中文候选 20sp", 20, KeyboardAppearance.read(context).candidateSize)
        Preferences.save(context, JSONObject().put("appearance", JSONObject().put("candidateSize", 27)))
        assertEquals("升级保留用户调节值", 27, KeyboardAppearance.read(context).candidateSize)
    }
    @Test fun offlineModelSearchOpensExistingDownloadPage() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.desc("搜索设置")), 10000))
        for (query in listOf("离线", "下载", "语音包")) {
            device.findObject(By.desc("搜索设置")).text = query
            assertTrue("搜索 $query 应找到模型管理", device.wait(Until.hasObject(By.text("离线语音包").clazz("android.widget.TextView")), 3000))
        }
        device.findObject(By.text("离线语音包").clazz("android.widget.TextView")).click()
        assertTrue("搜索结果应直达实际下载页", device.wait(Until.hasObject(By.desc("离线包状态")), 10000))
        assertTrue(device.hasObject(By.text("SenseVoice int8 + Silero VAD")))
        assertTrue(device.hasObject(By.text("立即下载")) || device.hasObject(By.text("删除离线语音包")))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "feedback-model-search.png"))
        device.setOrientationLeft()
        assertTrue("旋转后保持模型页", device.wait(Until.hasObject(By.desc("离线包状态")), 10000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("识别来源")), 10000))
        device.setOrientationNatural()
        assertTrue("退出模型页后旋转不能重新打开", device.wait(Until.hasObject(By.text("识别来源")), 10000))
        assertTrue(!device.hasObject(By.desc("离线包状态")))
    }
    @Test fun categoriesSearchRotationAndLearningGate() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("应有分类入口", device.wait(Until.hasObject(By.text("输入与候选")), 10000))
        for (name in listOf("键盘与外观", "词库与短语", "学习与隐私", "关于青学")) reveal(name)
        if (!device.hasObject(By.desc("搜索设置"))) UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(10)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "r2-settings-home.png"))
        val search = device.findObject(By.desc("搜索设置"))
        search.text = "模糊音"
        // 搜索框与结果标题同名，明确点击结果文字而不是重新聚焦输入框。
        device.wait(Until.findObject(By.text("模糊音").clazz("android.widget.TextView")), 10000)!!.click()
        val fuzzyOpened = device.wait(Until.hasObject(By.text("z / zh")), 10000)
        if (!fuzzyOpened) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "r2-search-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "r2-search-failure.xml"))
        }
        assertTrue("搜索结果应进入模糊音页", fuzzyOpened)
        tap("z / zh")
        assertTrue("模糊音开关应完成点击", device.wait(Until.hasObject(By.text("z / zh").checked(true)), 5000))
        assertTrue(Preferences.read(context).getBoolean("z_zh"))
        device.setOrientationLeft()
        assertTrue(device.wait(Until.hasObject(By.text("z / zh").checked(true)), 10000))
        device.setOrientationNatural(); device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.desc("输入方案")), 10000))
        device.pressBack(); tap("学习与隐私")
        assertTrue(device.wait(Until.hasObject(By.text("学习输入习惯").enabled(false)), 10000))
        tap("本机学习、词汇记录与统计")
        assertTrue(device.wait(Until.hasObject(By.text("学习输入习惯").enabled(true)), 10000))
        assertTrue(Preferences.read(context).getBoolean("learning"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "r2-learning.png"))
    }
}
