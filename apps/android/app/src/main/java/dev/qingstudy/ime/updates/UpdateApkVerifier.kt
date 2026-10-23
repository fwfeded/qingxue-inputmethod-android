//! Android 包解析验证应用身份、递增版本与当前安装证书，禁止调试包成为更新。
package dev.qingstudy.ime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object UpdateApkVerifier {
    @Suppress("DEPRECATION")
    private val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet()
    }
    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: UpdateRelease) {
        require(file.length() == release.bytes) { "安装包大小已变化" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包已变化，请重新下载" }
        val incoming = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("不能读取安装包")
        val current = installed(context)
        require(incoming.packageName == context.packageName && code(incoming) == release.code && code(incoming) > code(current)) { "安装包应用或版本不匹配" }
        require((incoming.applicationInfo?.flags ?: ApplicationInfo.FLAG_DEBUGGABLE) and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "不能将调试包作为在线更新" }
        require(certificates(incoming) == setOf(release.certificate) && certificates(current) == certificates(incoming)) { "安装证书不匹配，已保留当前版本；请勿卸载应用" }
    }
}
