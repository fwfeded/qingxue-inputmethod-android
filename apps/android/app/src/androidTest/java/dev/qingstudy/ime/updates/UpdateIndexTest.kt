//! 独立密钥签名夹具覆盖篡改、过期、渠道、设备过滤与来源约束。
package dev.qingstudy.ime

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class UpdateIndexTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key get() = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
    private fun payload() = JSONObject().put("schema", 1).put("packageName", "dev.qingstudy.ime")
        .put("issuedAt", 1000).put("expiresAt", 90000).put("releases", JSONArray())
    private fun signed(data: JSONObject): ByteArray {
        val bytes = data.toString().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
        return JSONObject().put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP)).toString().toByteArray()
    }
    private fun rejected(block: () -> Unit) { assertTrue("必须拒绝不可信的更新", runCatching(block).isFailure) }
    @Test fun signedEmptyCatalogAndTampering() {
        val envelope = signed(payload())
        assertTrue(UpdateIndex.verified(envelope, key, 2000).isEmpty())
        val modified = JSONObject(String(envelope)).put("payload", Base64.encodeToString(payload().put("schema", 2).toString().toByteArray(), Base64.NO_WRAP))
        rejected { UpdateIndex.verified(modified.toString().toByteArray(), key, 2000) }
        rejected { UpdateIndex.verified(envelope, key, 90000) }
        rejected { UpdateIndex.verified(signed(payload().put("issuedAt", 9999999)), key, 2000) }
        rejected { UpdateIndex.verified(signed(payload().put("packageName", "other.app")), key, 2000) }
    }
    @Test fun channelVersionAbiAndSdkFiltering() {
        val stable = UpdateRelease(36, "0.25", "stable", "arm64-v8a", 26,
            "https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/v0.25/app.apk", 10, "a".repeat(64), "b".repeat(64), "说明")
        val beta = stable.copy(code = 37, channel = "beta")
        assertEquals(stable, UpdateIndex.choose(listOf(stable, beta), "stable", 35, listOf("arm64-v8a"), 35))
        assertEquals(beta, UpdateIndex.choose(listOf(stable, beta), "beta", 35, listOf("arm64-v8a"), 35))
        assertNull(UpdateIndex.choose(listOf(beta), "beta", 37, listOf("arm64-v8a"), 35))
        assertNull(UpdateIndex.choose(listOf(beta), "beta", 35, listOf("x86_64"), 35))
        assertNull(UpdateIndex.choose(listOf(beta.copy(minSdk = 36)), "beta", 35, listOf("arm64-v8a"), 35))
    }
    @Test fun rejectUntrustedDownloadUrls() {
        val good = "https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/v1/app.apk"
        assertTrue(UpdateIndex.packageUrl(good))
        for (bad in listOf(good.replace("https", "http"), good.replace("github.com", "github.com.example.org"),
            good.replace("fwfeded", "other"), good + "?redirect=1", good.replace("/v1/", "/../"),
            good.replace("/v1/", "/%2e%2e/"), good.replace("github.com", "name@github.com"))) assertFalse(bad, UpdateIndex.packageUrl(bad))
    }
    @Test fun rejectsSignedInvalidManifestFields() {
        val entry = JSONObject().put("versionCode", 37).put("versionName", "test").put("channel", "beta").put("abi", "x86_64")
            .put("minSdk", 26).put("url", "https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/v1/app.apk")
            .put("bytes", 123).put("sha256", "a".repeat(64)).put("certificateSha256", "b".repeat(64))
        assertEquals(1, UpdateIndex.verified(signed(payload().put("releases", JSONArray().put(entry))), key, 2000).size)
        for ((field, value) in listOf("bytes" to 0, "channel" to "nightly", "abi" to "arm", "sha256" to "abc", "versionCode" to -1)) {
            val bad = JSONObject(entry.toString()).put(field, value)
            rejected { UpdateIndex.verified(signed(payload().put("releases", JSONArray().put(bad))), key, 2000) }
        }
    }
}
