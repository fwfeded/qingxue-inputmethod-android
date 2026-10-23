//! 使用真实按键触摸分发，验证一次触点只触发一次反馈及录音门控。
package dev.qingstudy.ime

import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyboardFeedbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private class Output : FeedbackOutput {
        val events = mutableListOf<String>()
        override fun haptic(view: View, followSystem: Boolean, strength: Int) { events.add("haptic:$followSystem:$strength") }
        override fun sound(kind: String, volume: Int, tone: String) { events.add("$kind:$volume") }
        override fun close() = Unit
    }
    private fun touch(key: KeyboardKey, action: Int, y: Float = 30f) {
        MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, 30f, y, 0).let { key.dispatchTouchEvent(it); it.recycle() }
    }
    @Test fun tapSwipeLongPressAndAccessibilityDoNotDoublePlay() {
        instrumentation.runOnMainSync {
            val output = Output()
            val feedback = KeyboardFeedback(context, output).apply { settings = FeedbackSettings(vibrate = true, sound = true) }
            var clicks = 0; var swipes = 0
            val key = KeyboardKey(context).apply {
                layout(0, 0, 100, 100); setOnClickListener { clicks++ }; onSwipe = { swipes++ }
                setOnLongClickListener { true }
            }
            feedback.bind(key)
            assertFalse(key.isSoundEffectsEnabled); assertFalse(key.isHapticFeedbackEnabled)
            touch(key, MotionEvent.ACTION_DOWN); touch(key, MotionEvent.ACTION_UP)
            assertEquals(1, clicks); assertEquals(listOf("haptic:true:2", "letter:30"), output.events)
            output.events.clear()
            touch(key, MotionEvent.ACTION_DOWN, 80f); touch(key, MotionEvent.ACTION_MOVE, 10f); touch(key, MotionEvent.ACTION_UP, 10f)
            assertEquals(1, swipes); assertEquals(2, output.events.size)
            output.events.clear()
            touch(key, MotionEvent.ACTION_DOWN); key.performLongClick(); touch(key, MotionEvent.ACTION_CANCEL)
            assertEquals(2, output.events.size)
            output.events.clear(); key.performClick()
            assertEquals("无障碍点击仍有反馈", 2, output.events.size)
            output.events.clear(); key.isEnabled = false
            touch(key, MotionEvent.ACTION_DOWN); key.performClick()
            assertEquals(0, output.events.size)
        }
    }
    @Test fun switchesZeroVolumeAndRecordingAreIndependent() {
        instrumentation.runOnMainSync {
            val output = Output()
            val feedback = KeyboardFeedback(context, output).apply { settings = FeedbackSettings() }
            val view = View(context)
            feedback.press(view); assertEquals(0, output.events.size)
            feedback.settings = FeedbackSettings(vibrate = true, followSystem = false, strength = 3, sound = true, volume = 60)
            feedback.press(view, "delete")
            assertEquals(listOf("haptic:false:3", "delete:60"), output.events)
            output.events.clear(); feedback.recording = true; feedback.press(view, "action")
            assertEquals(listOf("haptic:false:3"), output.events)
            output.events.clear(); feedback.recording = false; feedback.settings = feedback.settings.copy(vibrate = false, volume = 0)
            feedback.press(view); assertEquals(0, output.events.size)
            feedback.settings = feedback.settings.copy(volume = 10); feedback.press(view, "action")
            assertEquals(listOf("action:10"), output.events)
        }
    }
}
