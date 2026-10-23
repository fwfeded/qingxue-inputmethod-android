//! 保留用户设置；在真实 IME 中用私有调试音源完成触屏测试。
package dev.qingstudy.ime

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import java.io.File

open class OnlineUiFixture {
    @get:org.junit.Rule val testName=org.junit.rules.TestName()
    protected val inst=InstrumentationRegistry.getInstrumentation()
    protected val context=inst.targetContext
    protected val device=UiDevice.getInstance(inst)
    protected val host get()=By.res("dev.qingstudy.ime","test_input")
    private lateinit var original:String
    private var idle=0L;private var down=0L
    private var x=0f;private var y=0f
    private var sx=0f;private var sy=0f
    @Before fun setup() {
        original=VoiceSettings.read(context).toString()
        VoiceSettings.saveServer(context,"ws://10.0.2.2:10095","test-only-connection-secret")
        VoiceSettings.save(context,"bluetooth",false)
        File(context.filesDir,"debug-voice-enabled").delete()
        File(context.filesDir,"debug-online-enabled").writeText("explicit test")
        device.executeShellCommand("pm grant dev.qingstudy.ime android.permission.RECORD_AUDIO")
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
        device.executeShellCommand("ime set dev.qingstudy.ime/.PrototypeIme")
        idle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
    }
    @After fun cleanup() {
        shot("end-${testName.methodName}")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"online-end-${testName.methodName}.xml"))
        event(MotionEvent.ACTION_CANCEL);device.pressBack()
        Configurator.getInstance().waitForIdleTimeout=idle
        File(context.filesDir,"debug-online-enabled").delete()
        File(context.filesDir,"voice-settings.json").writeText(original)
    }
    protected fun launch(type:Class<*>) {
        val monitor=inst.addMonitor(type.name,null,false)
        try {
            context.startActivity(Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertNotNull("新页面未完成启动",inst.waitForMonitorWithTimeout(monitor,10000))
            inst.waitForIdleSync()
        } finally { inst.removeMonitor(monitor) }
    }
    protected fun editor() {
        launch(TryInputActivity::class.java);device.wait(Until.findObject(host),10000)!!.click();key("按键 空格")
        device.waitForIdle(1000);SystemClock.sleep(250)
    }
    protected fun key(desc:String)=device.wait(Until.findObject(By.desc(desc).enabled(true)),15000) ?: run {
        shot("missing-node");device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"online-missing-node.xml"));error(desc)
    }
    protected fun tap(desc:String) {
        repeat(3) { try { key(desc).click();return } catch(_:StaleObjectException) {} };error(desc)
    }
    protected fun event(kind:Int) {
        MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,x,y,0).also { it.source=InputDevice.SOURCE_TOUCHSCREEN;inst.uiAutomation.injectInputEvent(it,true);it.recycle() }
    }
    protected fun hold(waitForPanel:Boolean=true) {
        val b=key("按键 空格").visibleBounds;x=b.centerX().toFloat();y=b.centerY().toFloat();sx=x;sy=y
        down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN);if(waitForPanel)key("语音 cancel")
    }
    private fun move(tx:Float,ty:Float) {
        val ox=x;val oy=y
        for(i in 1..10) { x=ox+(tx-ox)*i/10;y=oy+(ty-oy)*i/10;event(MotionEvent.ACTION_MOVE);SystemClock.sleep(16) }
    }
    protected fun side(name:String) { val b=key("语音 $name").visibleBounds;move(b.centerX().toFloat(),b.centerY().toFloat()) }
    protected fun centre() = move(sx,sy)
    protected fun release() { event(MotionEvent.ACTION_UP);key("按键 空格") }
    protected fun text()=device.findObject(host).text.orEmpty()
    protected fun shot(name:String) { SystemClock.sleep(250);device.takeScreenshot(File(context.getExternalFilesDir(null),"online-$name.png")) }
    protected fun scroll(label:String) {
        if (!device.hasObject(By.text(label))) {
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(label)
            device.waitForIdle(1000);SystemClock.sleep(500)
        }
    }
}
