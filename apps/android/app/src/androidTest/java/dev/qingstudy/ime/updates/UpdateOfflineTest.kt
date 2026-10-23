//! 模拟器关闭网络后的检查失败与恢复重试，不使用模拟 HTTP 结果。
package dev.qingstudy.ime

import android.provider.Settings
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test

class UpdateOfflineTest {
    @Test fun offlineFailureThenRealNetworkRetry() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val wifi = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0)
        val mobile = Settings.Global.getString(context.contentResolver, "mobile_data")
        val prefs = UpdatePreferences.store(context)
        val saved = HashMap(prefs.all)
        fun state(): UpdateState { var value = UpdateState(); inst.runOnMainSync { value = UpdateManager.state }; return value }
        fun await(timeout: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return true; SystemClock.sleep(100) }
            return condition()
        }
        fun restoreNetwork() {
            device.executeShellCommand("svc wifi ${if (wifi == 1) "enable" else "disable"}")
            device.executeShellCommand("svc data ${if (mobile == "1") "enable" else "disable"}")
            if (mobile == null) device.executeShellCommand("settings delete global mobile_data")
        }
        try {
            inst.runOnMainSync { UpdateManager.reset(); UpdateManager.autoCheck(context) }
            // 此模拟器原设置自动检查关闭；测试本身不改变这项用户选择。
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            assertTrue("等待实际断网", await(10000) { !UpdatePreferences.network(context) })
            inst.runOnMainSync { UpdateManager.reset(); UpdateManager.check(context) }
            assertTrue("断网应返回可重试错误", await(30000) { state().phase == "error" })
            restoreNetwork()
            assertTrue("等待网络恢复", await(20000) { UpdatePreferences.network(context) })
            inst.runOnMainSync { UpdateManager.check(context) }
            assertTrue("恢复后校验真实发布源：${state()}", await(45000) { state().phase in listOf("current", "new", "incompatible") })
        } finally {
            restoreNetwork()
            inst.runOnMainSync { UpdateManager.reset() }
            val edit = prefs.edit().clear()
            saved.forEach { (k, v) -> when (v) { is Boolean -> edit.putBoolean(k,v); is Long -> edit.putLong(k,v); is String -> edit.putString(k,v) } }
            edit.commit()
        }
    }
}
