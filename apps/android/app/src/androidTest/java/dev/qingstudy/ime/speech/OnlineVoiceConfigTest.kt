//! 实际输入边界与隐私约束；不依赖网络状态。
package dev.qingstudy.ime

import org.junit.Assert.*
import org.junit.Test

class OnlineVoiceConfigTest {
    private val key = "test-only-connection-secret"
    @Test fun acceptsPrivateLanOrSecureService() {
        listOf("ws://192.168.1.8:10095", "ws://10.0.2.2:10095", "ws://172.16.0.1:10095", "wss://voice.example.com/asr").forEach {
            assertEquals(it, OnlineVoiceConfig.validate(it, key).url)
        }
    }
    @Test fun rejectsLoopbackPublicCleartextAndCredentialUrls() {
        listOf("ws://127.0.0.1:10095", "ws://localhost", "wss://[::1]", "ws://8.8.8.8", "ws://example.com",
            "ws://172.32.0.1", "ws://192.168.1.8:0", "ws://192.168.1.8:99999", "ws://user:pass@192.168.1.8",
            "ws://192.168.1.8?token=x", "ws://192.168.1.8#fragment", "https://example.com", "ws://010.0.0.1").forEach {
            assertTrue("应拒绝：$it", runCatching { OnlineVoiceConfig.validate(it, key) }.isFailure)
        }
    }
    @Test fun keysCannotInjectHeadersOrBeEmpty() {
        listOf("", "short", key+"\r\nX-Header:yes", "中文密钥", "x".repeat(257)).forEach {
            assertTrue(runCatching { OnlineVoiceConfig.validate("ws://10.0.2.2:10095", it) }.isFailure)
        }
    }
}
