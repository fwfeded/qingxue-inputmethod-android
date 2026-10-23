// 重建 IME 时旧加载任务和新实例可能重叠；两个真实 Worker 必须可同时装配公开词库。
package dev.qingstudy.ime
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ColdStartTest {
    @Test fun overlappingServiceLoadsDoNotCorruptSharedAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(3) {
            val ready = CountDownLatch(2)
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val workers = List(2) { EngineWorker(context) { error ->
                if (error != null) errors.add(error)
                ready.countDown()
            } }
            try {
                assertTrue("两个加载任务应结束", ready.await(60, TimeUnit.SECONDS))
                assertTrue("重叠加载失败：$errors", errors.isEmpty())
                val results = CountDownLatch(2)
                workers.forEach { worker -> worker.step("input", "nihao") { frame ->
                    if (frame.isFailure || frame.getOrNull()?.candidates?.any { candidate -> candidate.first == "你好" } != true) errors.add("未产生你好")
                    results.countDown()
                } }
                assertTrue(results.await(20, TimeUnit.SECONDS))
                assertTrue(errors.toString(), errors.isEmpty())
            } finally { workers.forEach { it.close() } }
        }
    }
}
