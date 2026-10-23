//! 在模拟器检查真实设置入口、偏好持久化和公开签名索引。
package dev.qingstudy.ime

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Direction
import org.junit.Assert.*
import org.junit.Test

class UpdateUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    @Test fun publishedCatalogOverHttps() {
        val key = context.assets.open("updates/public-key.txt").bufferedReader().use { it.readText().trim() }
        val releases = UpdateIndex.verified(UpdateTransport().index(java.util.concurrent.atomic.AtomicBoolean(false)), key)
        assertTrue(releases.isEmpty())
    }
    @Test fun settingsPreferencesAndRealSignedCatalog() {
        val prefs = UpdatePreferences.store(context)
        val saved = HashMap(prefs.all)
        try {
            prefs.edit().clear().commit()
            inst.runOnMainSync { UpdateManager.reset() }
            context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("page", "about").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.wait(Until.findObject(By.text("检查更新")), 10000)!!.click()
            device.waitForIdle()
            assertNotNull(device.wait(Until.findObject(By.text("应用更新")), 5000))
            val auto = device.wait(Until.findObject(By.text("自动检查更新")), 5000)!!
            assertFalse(auto.isChecked); auto.click()
            assertTrue(device.wait(Until.hasObject(By.text("自动检查更新").checked(true)), 5000))
            assertTrue(prefs.getBoolean("auto", false))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("关于青学")), 5000))
            device.waitForIdle()
            device.wait(Until.findObject(By.text("检查更新")), 5000)!!.click()
            device.waitForIdle()
            assertTrue(device.wait(Until.findObject(By.text("自动检查更新")), 5000)!!.isChecked)
            device.findObject(By.text("自动检查更新")).click()
            assertTrue(device.wait(Until.hasObject(By.text("自动检查更新").checked(false)), 5000))
            device.findObject(By.text("检查更新")).click()
            val connected = device.wait(Until.hasObject(By.text("发布源已连接，尚无可供下载的正式版本")), 45000)
            assertTrue("真实 GitHub HTTPS 索引状态：${UpdateManager.state}", connected)
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "update-connected.png"))
            device.findObject(By.clazz("android.widget.ScrollView")).scroll(Direction.DOWN, .8f)
            device.waitForIdle()
            device.wait(Until.findObject(By.text("更新渠道 · 测试版")), 5000)!!.click()
            device.wait(Until.findObject(By.text("稳定版")), 5000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("更新渠道 · 稳定版")), 5000))
            assertEquals("stable", prefs.getString("channel", ""))
            val wifi = device.wait(Until.findObject(By.text("仅在 Wi-Fi 下下载")), 5000)!!
            assertTrue(wifi.isChecked); wifi.click()
            assertTrue(device.wait(Until.hasObject(By.text("仅在 Wi-Fi 下下载").checked(false)), 5000))
            assertFalse(prefs.getBoolean("wifi", true))
        } finally {
            inst.runOnMainSync { UpdateManager.reset() }
            val edit = prefs.edit().clear()
            saved.forEach { (k, v) -> when (v) { is Boolean -> edit.putBoolean(k,v); is Long -> edit.putLong(k,v); is String -> edit.putString(k,v) } }
            edit.commit()
        }
    }
}
