//! 真实绑定服务取消测试：取消后 loading、partial 和 result 都不能回流。
package dev.qingstudy.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeClientTest {
    @Test fun cancelBeforeBindingSuppressesEveryLateCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val callbacks = AtomicInteger()
        lateinit var client: VoiceClient
        instrumentation.runOnMainSync {
            client = VoiceClient(instrumentation.targetContext, object : VoiceClient.Listener {
                override fun onState(state: String, message: String) { callbacks.incrementAndGet() }
                override fun onPartial(text: String) { callbacks.incrementAndGet() }
                override fun onResult(text: String, decodeMs: Long) { callbacks.incrementAndGet() }
                override fun onMetrics(loadMs: Long, reused: Boolean) { callbacks.incrementAndGet() }
            })
            client.start()
            client.cancel()
            callbacks.set(0)
        }
        try {
            Thread.sleep(2000)
            instrumentation.waitForIdleSync()
            assertEquals(0, callbacks.get())
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
}
