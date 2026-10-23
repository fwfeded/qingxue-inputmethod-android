//! 设置回归：强度切回初值必须保存，搜索直达反馈，许可入口不能闪退；恢复原配置。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
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

class SettingsRepairTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private lateinit var saved: JSONObject
    @Before fun setup() {
        saved = Preferences.read(context)
        Preferences.save(context, JSONObject().put("mixed", false))
        device.setOrientationNatural()
    }
    @After fun restore() { Preferences.save(context, saved); device.unfreezeRotation() }
    private fun open(type: Class<*>, page: String? = null): android.app.Activity {
        val activity = inst.startActivitySync(Intent(context, type).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync(); device.waitForIdle()
        return activity
    }
    private fun reveal(desc: String) {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(desc))
        device.waitForIdle()
        var previous = android.graphics.Rect()
        var same = 0
        repeat(40) {
            val rect = device.findObject(By.desc(desc))?.visibleBounds ?: android.graphics.Rect()
            same = if (!rect.isEmpty && rect == previous) same + 1 else 0
            if (same >= 5) return
            previous = rect; SystemClock.sleep(100)
        }
        error("控件尚未稳定：$desc")
    }
    @Test fun strengthRoundTripPersistsAndReopens() {
        FeedbackSettings.save(context, FeedbackSettings(vibrate = true, followSystem = false, strength = 2))
        open(AppearanceActivity::class.java)
        device.findObject(By.text("按键反馈")).click(); device.waitForIdle()
        for ((value, title) in listOf(3 to "明显", 2 to "适中", 1 to "轻柔", 2 to "适中")) {
            reveal("震动强度")
            device.findObject(By.desc("震动强度")).click()
            device.wait(Until.findObject(By.text(title)), 3000)!!.click(); device.waitForIdle()
            assertEquals("切回 $title 后必须保存，不能只改变显示", value, FeedbackSettings.read(context).strength)
        }
        open(AppearanceActivity::class.java)
        device.findObject(By.text("按键反馈")).click(); device.waitForIdle(); reveal("震动强度")
        assertTrue(device.hasObject(By.text("适中")))
        assertEquals(2, FeedbackSettings.read(context).strength)
        assertFalse(Preferences.read(context).getBoolean("mixed"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "settings-repair-strength.png"))
    }
    @Test fun searchFeedbackSynonymsOpensFeedbackTab() {
        open(SettingsActivity::class.java)
        for (query in listOf("震动", "振动", "音效", "音量")) {
            device.findObject(By.desc("搜索设置")).text = query
            assertTrue("搜索 $query 应提供反馈入口", device.wait(Until.hasObject(By.text("按键反馈").clazz("android.widget.TextView")), 3000))
        }
        device.findObject(By.text("按键反馈").clazz("android.widget.TextView")).click()
        assertTrue("结果应直达反馈页", device.wait(Until.hasObject(By.text("按键震动")), 5000))
        assertFalse(device.findObject(By.text("震动跟随系统")).isEnabled)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "settings-repair-search.png"))
    }
    @Test fun licenseEntryShowsBundledDataNotice() {
        val activity = open(SettingsActivity::class.java, "about")
        // 调用真实入口并捕获异常，避免资源回归杀死进程而无法恢复用户设置。
        fun find(view: android.view.View): android.view.View? {
            if (view is android.widget.TextView && view.text.toString() == "开源与数据许可") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var failure: Throwable? = null
        inst.runOnMainSync {
            failure = runCatching {
                var row = requireNotNull(find(activity.window.decorView))
                while (!row.isClickable) row = row.parent as android.view.View
                row.performClick()
            }.exceptionOrNull()
        }
        assertNull("许可入口打开失败：$failure", failure)
        assertTrue("许可入口应打开对话框而非因缺文件闪退", device.wait(Until.hasObject(By.text("关闭")), 5000))
        val message = device.findObject(By.res("android:id/message")).text
        assertTrue(message.contains("词频") || message.contains("CEFR"))
        assertTrue(message.contains("GNU") || message.contains("MIT") || message.contains("Apache"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "settings-repair-license.png"))
        device.findObject(By.text("关闭")).click()
    }
}
