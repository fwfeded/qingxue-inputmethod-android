//! V4 连续手势与提前松手：使用实际输入法和固定 PCM，不依赖网络识别。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceMotionV4Test {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val host = By.res("dev.qingstudy.ime", "test_input")
    private lateinit var settings: String
    private var idleTimeout=0L
    @Before fun setup() {
        idleTimeout=androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout
        settings = VoiceSettings.read(context).toString()
        VoiceSettings.save(context, "provider", "offline")
        assertTrue(File(context.filesDir, "debug-voice-input.wav").isFile)
        File(context.filesDir, "debug-voice-enabled").writeText("explicit test")
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
    }
    @After fun cleanup() {
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout=idleTimeout
        device.pressBack()
        File(context.filesDir, "debug-voice-enabled").delete()
        File(context.filesDir, "voice-settings.json").writeText(settings)
    }
    @Test fun upperHalfRegionsAreActionsAndMovingBackCancelsThem() {
        inst.runOnMainSync {
            val panel = VoicePanelView(context) { _, _ -> }
            panel.mode(true, "发送")
            panel.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 540, 420)
            assertEquals("cancel", panel.hover(40f, 50f))
            assertEquals("finish", panel.hover(270f, 405f))
            assertEquals("send", panel.hover(500f, 50f))
            assertEquals("finish", panel.hover(270f, 405f))
        }
    }
    @Test fun releaseRestoresKeyboardBeforeFinalResultAndPreservesTail() {
        context.startActivity(Intent(context, TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(host), 15000)!!.click()
        assertNotNull(device.wait(Until.findObject(By.desc("按键 空格")), 20000))
        device.waitForIdle()
        val space = device.findObject(By.desc("按键 空格")).visibleBounds
        val down = SystemClock.uptimeMillis()
        fun event(kind: Int) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, space.centerX().toFloat(), space.centerY().toFloat(), 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; inst.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN)
        try { assertNotNull(device.wait(Until.findObject(By.desc("语音 cancel")), 5000)); SystemClock.sleep(1200) }
        finally { androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout=0;event(MotionEvent.ACTION_UP) }
        val released = SystemClock.uptimeMillis()
        val restored = device.wait(Until.hasObject(By.desc("按键 空格")), 350)
        File(context.getExternalFilesDir(null), "v4-release-ms.txt").writeText((SystemClock.uptimeMillis()-released).toString())
        assertTrue("松手应立即恢复键盘，不能等待最终解码", restored)
        assertTrue("恢复耗时超过350ms",SystemClock.uptimeMillis()-released<350)
        assertTrue(device.wait(Until.hasObject(host.textContains("你好")), 15000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "voice-v4-release.png"))
    }
}
