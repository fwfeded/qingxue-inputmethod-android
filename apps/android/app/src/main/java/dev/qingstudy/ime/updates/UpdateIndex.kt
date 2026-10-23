//! 先验签再解析的 Android 发布索引；只接受青学仓库的 HTTPS 安装包。
package dev.qingstudy.ime

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object UpdateIndex {
    const val REPOSITORY = "https://github.com/fwfeded/qingxue-inputmethod-android"
    const val URL = "https://raw.githubusercontent.com/fwfeded/qingxue-inputmethod-android/main/updates/index.json"
    const val MAX_INDEX = 256 * 1024
    const val MAX_APK = 200L * 1024 * 1024
    fun verified(bytes: ByteArray, publicKey: String, now: Long = System.currentTimeMillis() / 1000): List<UpdateRelease> {
        require(bytes.size <= MAX_INDEX) { "版本清单过大" }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        val signature = Base64.decode(envelope.getString("signature"), Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT)))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(payload); verify(signature)
        }) { "版本清单签名不匹配" }
        val data = JSONObject(String(payload, Charsets.UTF_8))
        require(data.getInt("schema") == 1 && data.getString("packageName") == "dev.qingstudy.ime") { "不兼容的版本清单" }
        val issued = data.getLong("issuedAt")
        val expires = data.getLong("expiresAt")
        require(issued <= now + 86400 && expires > now && expires > issued && expires - issued <= 93 * 86400) { "版本清单已过期或设备时间不正确" }
        val items = data.getJSONArray("releases")
        require(items.length() <= 100) { "版本条目过多" }
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            UpdateRelease(item.getLong("versionCode"), item.getString("versionName"), item.getString("channel"),
                item.getString("abi"), item.getInt("minSdk"), item.getString("url"), item.getLong("bytes"),
                item.getString("sha256"), item.getString("certificateSha256"), item.optString("notes")).also { r ->
                require(r.code > 0 && r.name.length in 1..80 && r.notes.length <= 4000 && r.minSdk >= 26) { "无效的版本信息" }
                require(r.channel in listOf("stable", "beta") && r.abi in listOf("arm64-v8a", "x86_64")) { "无效的渠道或架构" }
                require(r.bytes in 1..MAX_APK && listOf(r.sha256, r.certificate).all { it.matches(Regex("[a-f0-9]{64}")) }) { "无效的校验信息" }
                require(packageUrl(r.url)) { "安装包地址不属于青学发布仓库" }
            }
        }
    }
    fun choose(releases: List<UpdateRelease>, channel: String, code: Long, abis: List<String>, sdk: Int): UpdateRelease? =
        releases.filter { it.code > code && it.minSdk <= sdk && it.abi in abis && (it.channel == "stable" || channel == "beta") }
            .sortedWith(compareByDescending<UpdateRelease> { it.code }.thenBy { abis.indexOf(it.abi) }).firstOrNull()

    fun packageUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
            uri.rawQuery == null && uri.rawPath.startsWith("/fwfeded/qingxue-inputmethod-android/releases/download/") &&
            !uri.rawPath.contains("%") && !uri.rawPath.split('/').any { it == ".." || it == "." } && uri.rawPath.endsWith(".apk")
    }.getOrDefault(false)
}
