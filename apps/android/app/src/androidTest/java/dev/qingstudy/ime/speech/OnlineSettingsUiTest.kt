//! 真正的设置页检测、参数变更失效、失败恢复和来源切换。
package dev.qingstudy.ime

import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Assert.*

class OnlineSettingsUiTest : OnlineUiFixture() {
    private fun textButton(label:String)=device.wait(Until.findObject(By.text(label)),8000) ?: error(label)
    private fun openServer() {
        launch(VoiceSettingsActivity::class.java)
        textButton("识别来源")
        textButton("电脑语音服务").click();key("语音服务器地址")
    }
    private fun check() { if(device.hasObject(By.desc("按键 空格")))device.pressBack();scroll("检测连接");textButton("检测连接").click() }
    @Test fun keyIsMaskedUnlessUserShowsIt() {
        openServer()
        fun masked(expected:Boolean) {
            inst.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first { it is VoiceSettingsActivity }
                fun field(view:android.view.View):android.widget.EditText? {
                    if(view is android.widget.EditText && view.contentDescription=="语音连接密钥") return view
                    if(view is android.view.ViewGroup) for(i in 0 until view.childCount) field(view.getChildAt(i))?.let { return it }
                    return null
                }
                val input=field(activity.window.decorView)!!
                val shown=input.transformationMethod?.getTransformation(input.text,input)?.toString() ?: input.text.toString()
                assertEquals(expected,!shown.contains("test-only-connection-secret"))
            }
        }
        masked(true);textButton("显示密钥").click();masked(false);textButton("显示密钥").click()
        assertTrue(device.wait(Until.hasObject(By.text("显示密钥").checked(false)),2000));masked(true)
        android.os.SystemClock.sleep(250)
        shot("key-masked")
    }
    @Test fun successfulProbeInvalidatedByEditAndOnlyThenEnablesOnline() {
        VoiceSettings.save(context,"provider","offline")
        val models=VoiceModels.isReady(context)
        openServer();check()
        assertTrue(device.wait(Until.hasObject(By.desc("服务器连接状态").textContains("连接成功")),8000));shot("connection-ready")
        assertTrue(textButton("保存并使用在线语音").isEnabled)
        scroll("服务器地址");key("语音服务器地址").text="ws://10.0.2.2:10095/"
        if(device.hasObject(By.desc("按键 空格")))device.pressBack();scroll("保存并使用在线语音");assertFalse(textButton("保存并使用在线语音").isEnabled)
        assertEquals("offline",VoiceSettings.read(context).getString("provider"))
        textButton("检测连接").click();assertTrue(device.wait(Until.hasObject(By.desc("服务器连接状态").textContains("连接成功")),8000))
        textButton("保存并使用在线语音").click();assertEquals("online",VoiceSettings.read(context).getString("provider"))
        textButton("离线语音 · 此手机").click();assertEquals("offline",VoiceSettings.read(context).getString("provider"))
        assertEquals(models,VoiceModels.isReady(context));shot("source-offline")
    }
    @Test fun wrongKeyAndUnreachableAddressAreRecoverable() {
        openServer();key("语音连接密钥").text="invalid-test-connection-secret";check()
        assertTrue(device.wait(Until.hasObject(By.desc("服务器连接状态").textContains("密钥不正确")),8000));shot("wrong-key")
        assertFalse(textButton("保存并使用在线语音").isEnabled)
        scroll("服务器地址");key("语音服务器地址").text="ws://10.0.2.2:10998";check()
        assertTrue(device.wait(Until.hasObject(By.desc("服务器连接状态").textContains("连接")),8000))
        scroll("服务器地址");key("语音服务器地址").text="ws://10.0.2.2:10095"
        key("语音连接密钥").text="test-only-connection-secret";check()
        assertTrue(device.wait(Until.hasObject(By.desc("服务器连接状态").textContains("连接成功")),8000))
    }
}
