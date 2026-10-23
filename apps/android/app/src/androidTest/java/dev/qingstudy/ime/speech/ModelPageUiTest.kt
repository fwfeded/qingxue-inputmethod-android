//! 真实模型管理页面的下载、暂停与清理；旧包备份后恢复，仅操作模拟器测试数据。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ModelPageUiTest {
    @Test fun userCanPauseAndRemovePartialDownload() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        inst.runOnMainSync { VoiceClient(context, EmptyVoiceListener()).apply { release(); close() } }
        val until = SystemClock.uptimeMillis() + 5000
        while (device.executeShellCommand("pidof dev.qingstudy.ime:voice").trim().isNotEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        val target = VoiceModels.directory(context)
        val backup = File(context.cacheDir, "page-model-backup")
        assertTrue(VoiceModels.isReady(context)); check(!backup.exists()); check(target.renameTo(backup))
        try {
            context.startActivity(Intent(context, VoiceSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("离线语音包")))
            device.waitForIdle(); device.findObject(By.text("离线语音包")).click()
            device.wait(Until.findObject(By.text("Wi-Fi 下载")), 5000)!!.click()
            assertNotNull(device.wait(Until.findObject(By.text("暂停下载")), 5000))
            val started = SystemClock.uptimeMillis() + 25000
            while (!VoiceModels.hasPartial(context) && SystemClock.uptimeMillis() < started) SystemClock.sleep(100)
            assertTrue("正式页面应开始下载", VoiceModels.hasPartial(context))
            device.findObject(By.text("暂停下载")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("离线包状态").textContains("已暂停")), 20000))
            assertFalse(VoiceModels.isReady(context))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "r5-model-paused.png"))
            device.findObject(By.text("删除已下载片段")).click()
            device.wait(Until.findObject(By.text("删除").clickable(true)), 5000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("离线包状态").textContains("已删除")), 10000))
            assertFalse(VoiceModels.hasPartial(context)); assertFalse(VoiceModels.isReady(context))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "r5-model-deleted.png"))
        } finally {
            device.pressBack(); device.pressBack()
            VoiceModels.locked(context, { false }) { /* wait until page's canceled transfer releases files */ }
            VoiceModels.delete(context); check(backup.renameTo(target))
        }
    }
}
