//! 轻量安装包和宿主动作的真实边界，旧包先运行以保留失败证据。
package dev.qingstudy.ime

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R5ReleaseTest {
    @Test fun loadingVoiceEntryDoesNotAcceptAnActionThatCannotStart() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val keyboard = KeyboardView(inst.targetContext) { _, _ -> }
            val voice = keyboard.findViewWithTag<android.view.View>("voice-entry")
            assertFalse("词库加载时语音入口必须禁用，与空格键保持一致", voice.isEnabled)
            keyboard.render(Frame("ni", emptyList(), "", false))
            assertTrue("词库就绪后恢复语音入口", voice.isEnabled)
            keyboard.status("加载失败", false)
            assertFalse("加载失败时不得接受无效语音点击", voice.isEnabled)
        }
    }

    @Test fun baseApkDoesNotContainOfflineModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("基础APK不得内置229MiB语音模型", context.assets.list("voice").orEmpty().contains("model.int8.onnx"))
    }

    @Test fun returnKeyShowsHostSendAction() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        inst.context.startActivity(Intent(inst.context, PhoneFixtureActivity::class.java)
            .putExtra("mode", "send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val field = device.wait(Until.findObject(By.desc("手机测试输入框")), 15000)
            ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15000)!!
        field.click()
        assertTrue("发送宿主的回车标签必须为发送", device.wait(Until.hasObject(By.text("发送").enabled(true)), 15000))
        device.findObject(By.text("发送").enabled(true)).click()
        assertTrue(device.wait(Until.hasObject(By.text("已触发动作 4")), 5000))
    }
    @Test fun searchLabelAndActionStayConsistent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        inst.context.startActivity(Intent(inst.context, PhoneFixtureActivity::class.java)
            .putExtra("mode", "search").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.desc("手机测试输入框")), 15000)!!.click()
        device.wait(Until.findObject(By.text("搜索").enabled(true)), 15000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("已触发动作 3")), 5000))
    }
    @Test fun multilineDoesNotPromiseOrExecuteSend() {
        val info = android.view.inputmethod.EditorInfo().apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        org.junit.Assert.assertEquals("换行", EditorAction.label(info))
        org.junit.Assert.assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_NONE, EditorAction.action(info))
    }
}
