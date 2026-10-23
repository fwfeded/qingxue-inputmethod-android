//! 实际触屏连续移动、尾音后的键盘输入与焦点切换回归。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

open class VoiceGestureV4Test {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private val host get()=By.res("dev.qingstudy.ime","test_input")
    private lateinit var saved:String
    private lateinit var appearance:KeyboardAppearance
    private var idle=0L
    protected lateinit var editor: android.widget.EditText
    private var down=0L
    private var x=0f;private var y=0f;private var sx=0f;private var sy=0f
    @Before fun setup() {
        // 私有测试标记显式选择离线音源；关闭自动预热以覆盖冷加载期间的手势交错。
        saved=VoiceSettings.read(context).toString();VoiceSettings.save(context,"provider","system")
        appearance=KeyboardAppearance.read(context)
        InstrumentationRegistry.getArguments().getString("v4Theme")?.let { KeyboardAppearance.save(context,appearance.copy(theme=it)) }
        assertTrue(File(context.filesDir,"debug-voice-input.wav").isFile)
        File(context.filesDir,"debug-voice-enabled").writeText("explicit test")
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        idle=Configurator.getInstance().waitForIdleTimeout
        val monitor=inst.addMonitor(TryInputActivity::class.java.name,null,false)
        try {
            context.startActivity(Intent(context,TryInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val activity = inst.waitForMonitorWithTimeout(monitor,10000)
            assertNotNull(activity)
            inst.runOnMainSync { editor = activity.findViewById(R.id.test_input); editor.hint = "" }
            inst.waitForIdleSync()
        } finally { inst.removeMonitor(monitor) }
        device.wait(Until.findObject(host),15000)!!.click()
        assertNotNull(device.wait(Until.findObject(By.desc("按键 空格").enabled(true)),20000));device.waitForIdle()
        SystemClock.sleep(250)
        Configurator.getInstance().waitForIdleTimeout=0
    }
    @After fun cleanup() {
        event(MotionEvent.ACTION_CANCEL);device.pressBack()
        Configurator.getInstance().waitForIdleTimeout=idle
        File(context.filesDir,"debug-voice-enabled").delete();File(context.filesDir,"voice-settings.json").writeText(saved)
        KeyboardAppearance.save(context,appearance)
    }
    private fun key(name:String)=device.wait(Until.findObject(By.desc(name).enabled(true)),15000) ?: error(name)
    private fun tap(name:String) {
        // 候选/尾音刷新会替换节点；只重取尚未注入点击的失效节点，不吞断言失败。
        repeat(3) { try { key(name).click();return } catch(_: androidx.test.uiautomator.StaleObjectException) { } }
        error("节点持续失效：$name")
    }
    private fun event(kind:Int) { MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,x,y,0).also { it.source=InputDevice.SOURCE_TOUCHSCREEN;inst.uiAutomation.injectInputEvent(it,true);it.recycle() } }
    private fun hold() {
        val bounds=key("按键 空格").visibleBounds;sx=bounds.centerX().toFloat();sy=bounds.centerY().toFloat();x=sx;y=sy
        down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN);assertNotNull(key("语音 cancel"))
    }
    private fun move(tx:Float,ty:Float) {
        val ox=x;val oy=y
        for(i in 1..10){x=ox+(tx-ox)*i/10;y=oy+(ty-oy)*i/10;event(MotionEvent.ACTION_MOVE);SystemClock.sleep(16)}
    }
    private fun side(name:String){val b=key("语音 $name").visibleBounds;move(b.centerX().toFloat(),b.centerY().toFloat())}
    private fun screenshot(name:String){device.takeScreenshot(File(context.getExternalFilesDir(null),"voice-v4-$name.png"))}
    @Test fun leftRightReturnThenReleaseKeepsTextAndGloss() {
        hold();assertTrue(device.wait(Until.hasObject(host.textContains("你好")),15000))
        screenshot("entered");side("cancel");SystemClock.sleep(220);screenshot("left")
        move(sx,sy);SystemClock.sleep(220);screenshot("return")
        side("send");SystemClock.sleep(220);screenshot("right")
        move(sx,sy);event(MotionEvent.ACTION_UP)
        assertNotNull(key("按键 空格"));SystemClock.sleep(700)
        val text=device.findObject(host).text
        assertTrue(text.contains("你好"));assertFalse(text.endsWith("\n"))
        "nihao".forEach { tap("按键 $it") }
        assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*"))),10000))
        tap("按键 空格");assertEquals(text+"你好",device.findObject(host).text)
        screenshot("gloss")
    }
    @Test fun typingImmediatelyAfterReleaseFollowsTailInsteadOfReplacingIt() {
        typeAfterRelease(1400)
    }
    protected fun typeAfterRelease(delay: Long, rapid: Boolean = false) {
        val before = device.findObject(host).text
        val points = if (rapid) "nihao".map { key("按键 $it").visibleBounds.let { b -> b.centerX() to b.centerY() } } else emptyList()
        hold();SystemClock.sleep(delay);event(MotionEvent.ACTION_UP)
        if (rapid) points.forEach { (px, py) ->
            val time = SystemClock.uptimeMillis()
            for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time,SystemClock.uptimeMillis(),kind,px.toFloat(),py.toFloat(),0).also {
                    it.source=InputDevice.SOURCE_TOUCHSCREEN;inst.uiAutomation.injectInputEvent(it,true);it.recycle()
                }
                SystemClock.sleep(12)
            }
        } else "nihao".forEach { tap("按键 $it") }
        val found=device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*"))),10000)
        if(!found) {
            screenshot("immediate-failed")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"voice-v4-immediate-failed.xml"))
        }
        assertTrue("松手后输入 nihao 未出现候选，宿主=${device.findObject(host)?.text}",found)
        tap("按键 空格")
        assertTrue(device.wait(Until.hasObject(host.textEndsWith("你好")),10000))
        val text=device.findObject(host).text
        assertTrue("尾字或新输入丢失：$text",text.startsWith(before)&&text.endsWith("你好")&&text.length>=before.length+2)
        if (delay >= 1400) assertTrue("语音结果未保留", text.length >= before.length + 4)
        SystemClock.sleep(1000);assertEquals(text,device.findObject(host).text)
    }
    @Test fun focusChangeAfterReleaseDoesNotWriteOldResultIntoPassword() {
        hold();SystemClock.sleep(300);event(MotionEvent.ACTION_UP)
        val intent=Intent().setClassName(inst.context.packageName,"dev.qingstudy.ime.PhoneFixtureActivity").putExtra("mode","password").putExtra("hideHint",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        inst.context.startActivity(intent)
        val field=device.wait(Until.findObject(By.desc("手机测试输入框")),10000)!!;field.click()
        assertNotNull(key("按键 空格"));SystemClock.sleep(2500)
        assertEquals("",device.findObject(By.desc("手机测试输入框")).text)
    }
    @Test fun actualCursorMoveAfterVoiceStillEndsPinyinComposition() {
        typeAfterRelease(1400, rapid = true)
        device.executeShellCommand("input text nihao")
        assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 你好，.*hello.*")).enabled(true)),10000))
        var before = ""
        inst.runOnMainSync { before = editor.text.toString(); editor.setSelection(0) }
        assertTrue(device.wait(Until.gone(By.desc(java.util.regex.Pattern.compile("候选 你好，.*"))),10000))
        device.executeShellCommand("input text wanshang")
        assertTrue(device.wait(Until.hasObject(By.desc(java.util.regex.Pattern.compile("候选 晚上，.*"))),10000))
        tap("按键 空格")
        assertTrue(device.wait(Until.hasObject(host.text("晚上$before")),10000))
    }
    @Test fun nextHeldGestureSurvivesPreviousFinalCallback() {
        hold();SystemClock.sleep(200);event(MotionEvent.ACTION_UP)
        val bounds=key("按键 空格").visibleBounds
        x=bounds.centerX().toFloat();y=bounds.centerY().toFloat();down=SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN)
        try { assertNotNull("尾音完成不能吞掉下一次仍按住的手势",device.wait(Until.findObject(By.desc("语音 cancel")),6000)) }
        finally { event(MotionEvent.ACTION_CANCEL) }
        assertNotNull(key("按键 空格"))
    }
    @Test fun hidingKeyboardKeepsKeysTypedWhileFinalWasPending() {
        hold();SystemClock.sleep(200);event(MotionEvent.ACTION_UP)
        tap("按键 x");tap("收起键盘")
        assertTrue("已点击的键不能因收起键盘丢失",device.wait(Until.hasObject(host.textEndsWith("x")),10000))
    }
    @Test fun systemBackKeepsKeysTypedWhileFinalWasPending() {
        hold();SystemClock.sleep(200);event(MotionEvent.ACTION_UP)
        tap("按键 x");device.pressBack()
        assertTrue("系统返回收起也不能丢掉已点击的键",device.wait(Until.hasObject(host.textEndsWith("x")),10000))
    }
}
