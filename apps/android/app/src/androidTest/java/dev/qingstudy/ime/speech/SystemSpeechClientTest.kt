//! 可控系统识别驱动只验证会话隔离、资源与状态，不代替真实提供方的中文效果验证。
package dev.qingstudy.ime

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSpeechClientTest {
    @Test fun listeningWaitsForProviderReadyAndRequestsChinesePartials() = main {
        val fixture = Fixture()
        fixture.client.start("model")
        assertEquals(listOf("loading"), fixture.events.states)
        val driver = fixture.drivers.single()
        assertEquals("zh-CN", driver.intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(driver.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        if (Build.VERSION.SDK_INT >= 33) assertEquals(RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY,
            driver.intent!!.getStringExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING))
        driver.listener.onReadyForSpeech(Bundle())
        assertEquals("listening", fixture.events.states.last())
        fixture.client.close()
    }

    @Test fun canceledGenerationCannotWriteIntoTheNextSession() = main {
        val fixture = Fixture()
        fixture.client.start("auto")
        val old = fixture.drivers.single()
        fixture.client.cancel()
        fixture.client.start("auto")
        val current = fixture.drivers.last()
        old.listener.onPartialResults(result("迟到文字"))
        old.listener.onResults(result("迟到结果"))
        old.listener.onError(SpeechRecognizer.ERROR_CLIENT)
        old.listener.onRmsChanged(10f)
        assertTrue(fixture.events.partials.isEmpty())
        assertTrue(fixture.events.results.isEmpty())
        assertEquals("loading", fixture.events.states.last())
        current.listener.onResults(result("新的结果"))
        assertEquals(listOf("新的结果"), fixture.events.results)
        assertEquals(1, old.canceled)
        assertEquals(1, old.closed)
        assertEquals(1, current.closed)
    }

    @Test fun stopAwaitsFinalResultAndDestroysExactlyOnce() = main {
        val fixture = Fixture()
        fixture.client.start("auto")
        val driver = fixture.drivers.single()
        driver.listener.onReadyForSpeech(Bundle())
        fixture.client.stop()
        assertEquals("processing", fixture.events.states.last())
        assertEquals(1, driver.stopped)
        assertEquals(0, driver.closed)
        driver.listener.onResults(result("完整结果"))
        fixture.tasks.advance(100_000)
        assertEquals(listOf("完整结果"), fixture.events.results)
        assertEquals(1, driver.closed)
        assertFalse(fixture.events.states.contains("error"))
    }

    @Test fun stoppingWithoutAProviderResultTimesOutAndClearsLevel() = main {
        val fixture = Fixture()
        fixture.client.start("auto")
        val driver = fixture.drivers.single()
        driver.listener.onReadyForSpeech(Bundle())
        driver.listener.onRmsChanged(8f)
        assertTrue(fixture.events.levels.last() > 0f)
        fixture.client.stop()
        fixture.tasks.advance(8_000)
        assertEquals("error", fixture.events.states.last())
        assertEquals(0f, fixture.events.levels.last())
        assertEquals(1, driver.closed)
        driver.listener.onResults(result("超时结果"))
        assertTrue(fixture.events.results.isEmpty())
    }

    @Test fun durationLimitStopsAndThenUsesBoundedResultWait() = main {
        val fixture = Fixture()
        fixture.client.start("auto")
        val driver = fixture.drivers.single()
        driver.listener.onReadyForSpeech(Bundle())
        fixture.tasks.advance(60_000)
        assertEquals(1, driver.stopped)
        assertEquals("processing", fixture.events.states.last())
        fixture.tasks.advance(8_000)
        assertEquals("error", fixture.events.states.last())
        assertEquals(1, driver.closed)
    }

    @Test fun unavailableServiceReportsAnActionableErrorWithoutCreatingADriver() = main {
        val fixture = Fixture(available = false)
        fixture.client.start("auto")
        assertEquals("error", fixture.events.states.last())
        assertTrue(fixture.events.messages.last().contains("语音设置"))
        assertTrue(fixture.drivers.isEmpty())
    }

    @Test fun providerErrorDisposesResourcesAndClosedClientCannotRestart() = main {
        val fixture = Fixture()
        fixture.client.start("auto")
        val driver = fixture.drivers.single()
        driver.listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals("error", fixture.events.states.last())
        assertEquals(1, driver.closed)
        fixture.client.close()
        fixture.client.start("auto")
        assertEquals(1, fixture.drivers.size)
    }

    @Test fun startupExceptionAndMissingReadyBothCleanUp() = main {
        val throwing = Fixture(throwOnStart = true)
        throwing.client.start("auto")
        assertEquals("error", throwing.events.states.last())
        assertEquals(1, throwing.drivers.single().closed)
        val hanging = Fixture()
        hanging.client.start("auto")
        hanging.tasks.advance(10_000)
        assertEquals("error", hanging.events.states.last())
        assertEquals(1, hanging.drivers.single().closed)
    }

    @Test fun formattedAndRawHypothesesRemainAlternativesAndEndOfSpeechHasATimeout() = main {
        val fixture = Fixture()
        fixture.client.start("model")
        val driver = fixture.drivers.single()
        driver.listener.onReadyForSpeech(Bundle())
        val texts = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("你好，世界。", "你好世界")) }
        driver.listener.onPartialResults(texts)
        assertEquals(listOf("你好，世界。"), fixture.events.partials)
        driver.listener.onEndOfSpeech()
        assertEquals("processing", fixture.events.states.last())
        driver.listener.onRmsChanged(9f)
        assertEquals(0f, fixture.events.levels.last())
        driver.listener.onResults(texts)
        assertEquals(listOf("你好，世界。"), fixture.events.results)
        val hanging = Fixture()
        hanging.client.start("all")
        hanging.drivers.single().listener.onEndOfSpeech()
        hanging.tasks.advance(8_000)
        assertEquals("error", hanging.events.states.last())
    }

    @Test fun diagnosticUsesCurrentFailureCodeWithoutRecordingCanceledCallback() = main {
        val fixture = Fixture()
        fixture.client.start("all")
        val canceled = fixture.drivers.single()
        fixture.client.cancel()
        canceled.listener.onError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(fixture.errors.isEmpty())
        fixture.client.start("all")
        fixture.drivers.last().listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED), fixture.errors)
        assertTrue(fixture.events.messages.last().contains("Android 错误码 ${SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED}"))
        fixture.client.close()
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    private class Fixture(available: Boolean = true, throwOnStart: Boolean = false) {
        val events = Events()
        val tasks = Tasks()
        val drivers = mutableListOf<Driver>()
        val errors = mutableListOf<Int>()
        val client = SystemSpeechClient(events, { available }, { listener ->
            Driver(listener, throwOnStart).also { drivers += it }
        }, tasks, { errors += it })
    }

    private class Driver(val listener: RecognitionListener, private val throwOnStart: Boolean) : SystemSpeechDriver {
        var intent: Intent? = null
        var stopped = 0
        var canceled = 0
        var closed = 0
        override fun start(intent: Intent) { this.intent = intent; if (throwOnStart) throw SecurityException("denied") }
        override fun stop() { stopped++ }
        override fun cancel() { canceled++ }
        override fun close() { closed++ }
    }

    private class Events : VoiceClient.Listener {
        val states = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val results = mutableListOf<String>()
        val levels = mutableListOf<Float>()
        override fun onState(state: String, message: String) { states += state; messages += message }
        override fun onPartial(text: String) { partials += text }
        override fun onResult(text: String, decodeMs: Long) { results += text }
        override fun onMetrics(loadMs: Long, reused: Boolean) = Unit
        override fun onLevel(level: Float) { levels += level }
    }

    private class Tasks : SystemSpeechScheduler {
        private var time = 0L
        private val pending = mutableMapOf<Runnable, Long>()
        override fun now() = time
        override fun postDelayed(task: Runnable, delayMs: Long) { pending[task] = time + delayMs }
        override fun remove(task: Runnable) { pending.remove(task) }
        fun advance(duration: Long) {
            val until = time + duration
            while (true) {
                val next = pending.minByOrNull { it.value }?.takeIf { it.value <= until } ?: break
                time = next.value; pending.remove(next.key); next.key.run()
            }
            time = until
        }
    }
}
