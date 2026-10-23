//! 正式 V4 手势、草稿替换、断线提示和新键入不被迟到校正覆盖。
package dev.qingstudy.ime

import android.os.SystemClock
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Assert.*

class OnlineVoiceFlowTest : OnlineUiFixture() {
    @Test fun sourceLabelAndFailureShortcutFollowSavedProvider() {
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10998","test-only-connection-secret")
        editor();assertTrue(device.wait(Until.hasObject(By.text("电脑语音")),5000));shot("source-online")
        hold(false);key("语音 server-settings");event(android.view.MotionEvent.ACTION_CANCEL)
        tap("语音 server-settings");assertEquals("ws://10.0.2.2:10998",key("语音服务器地址").text)
        VoiceSettings.save(context,"provider","offline")
        editor();assertTrue(device.wait(Until.hasObject(By.text("离线语音")),5000));shot("source-local")
    }
    @Test fun actualMicrophoneStartsAndReleasesWithoutOfflineProcess() {
        java.io.File(context.filesDir,"debug-online-enabled").delete()
        editor();hold();SystemClock.sleep(1200)
        assertFalse(device.hasObject(By.desc("语音 use-offline")))
        release();SystemClock.sleep(1200);key("按键 空格")
        assertFalse(device.executeShellCommand("ps -A").contains("dev.qingstudy.ime:voice"))
        shot("microphone-released")
    }
    @Test fun focusChangeDropsDelayedResultAndSend() {
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10096/delayed","test-only-connection-secret")
        editor();hold();assertTrue(device.wait(Until.hasObject(host.textContains("临时文字")),5000))
        side("send");release()
        inst.context.startActivity(android.content.Intent().setClassName(inst.context.packageName,"dev.qingstudy.ime.PhoneFixtureActivity")
            .putExtra("mode","password").putExtra("hideHint",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val field=device.wait(Until.findObject(By.desc("手机测试输入框")),10000)!!;field.click()
        SystemClock.sleep(2500);assertEquals("",device.findObject(By.desc("手机测试输入框")).text)
    }
    @Test fun realModelMoveLeftRightBackReleaseAndGloss() {
        editor();hold();assertTrue(device.wait(Until.hasObject(host.textContains("你好")),15000))
        shot("entered");side("cancel");SystemClock.sleep(220);shot("left")
        side("send");SystemClock.sleep(220);shot("right");centre();SystemClock.sleep(220);shot("centre")
        val start=SystemClock.elapsedRealtime();release()
        val elapsed=SystemClock.elapsedRealtime()-start
        assertTrue("松手恢复键盘耗时 $elapsed",elapsed<1500)
        SystemClock.sleep(1500);val voice=text();assertTrue(voice.contains("你好"));assertFalse(voice.endsWith("\n"))
        "nihao".forEach { tap("按键 $it") }
        assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*"))),10000))
        tap("按键 空格");assertEquals(voice+"你好",text());shot("gloss")
    }
    @Test fun leftReleaseRestoresAndRightReleaseSendsOnce() {
        editor();hold();assertTrue(device.wait(Until.hasObject(host.textContains("你好")),15000))
        side("cancel");release();SystemClock.sleep(600);assertEquals("点击这里开始输入",text())
        hold();assertTrue(device.wait(Until.hasObject(host.textContains("你好")),15000))
        side("send");release();assertTrue(device.wait(Until.hasObject(host.textContains("\n")),7000))
        assertEquals(1,text().count { it=='\n' });SystemClock.sleep(800);assertEquals(1,text().count { it=='\n' })
    }
    @Test fun newTypingCancelsDelayedCorrectionAndDelayedSend() {
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10096/delayed","test-only-connection-secret")
        editor();hold();assertTrue(device.wait(Until.hasObject(host.textContains("临时文字")),5000))
        side("send");release();tap("按键 x");tap("按键 空格")
        val typed=text();SystemClock.sleep(2500)
        assertEquals(typed,text());assertTrue(typed.startsWith("临时文字"));assertFalse(text().contains("校正"));assertFalse(text().contains("\n"))
    }
    @Test fun disconnectRetainsDraftThenReconnectsWithoutRestartingIme() {
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10096/drop","test-only-connection-secret")
        editor();hold(false);assertNotNull(device.wait(Until.findObject(By.desc("语音 use-offline")),8000))
        event(android.view.MotionEvent.ACTION_CANCEL);shot("disconnected")
        tap("语音 keyboard");assertTrue(text().contains("临时文字"))
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10095","test-only-connection-secret")
        hold();assertTrue(device.wait(Until.hasObject(host.textContains("你好")),15000));release();SystemClock.sleep(1000)
        assertTrue(text().startsWith("临时文字"));assertTrue(text().contains("你好"))
    }
    @Test fun correctionReplacesDraftInsteadOfAppending() {
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10096/delayed","test-only-connection-secret")
        editor();hold();assertTrue(device.wait(Until.hasObject(host.textContains("临时文字")),5000))
        release();assertTrue(device.wait(Until.hasObject(host.text("校正文字。")),6000))
        assertEquals("校正文字。",text())
    }
}
