//! 本地非调试升级夹具验证 Android 安装确认、取消与证书/版本保护。
package dev.qingstudy.ime

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

class UpdateInstallTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    @Suppress("DEPRECATION")
    private fun prepare(): UpdateRelease {
        val source = File(context.getExternalFilesDir(null), "updates-fixture.apk")
        check(source.exists()) { "先推送本地更高 versionCode 的非调试安装夹具" }
        val info = context.packageManager.getPackageArchiveInfo(source.path,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)!!
        val hash = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val n = input.read(buffer); if(n < 0) break; hash.update(buffer, 0, n) } }
        val release = UpdateRelease(UpdateApkVerifier.code(info), info.versionName!!, "beta", "x86_64", 26,
            "https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/test/app.apk", source.length(),
            hash.digest().joinToString("") { "%02x".format(it) }, UpdateApkVerifier.certificates(info).single(), "仅本地安装验证")
        val target = UpdateManager.apk(context)
        target.parentFile!!.mkdirs(); source.copyTo(target, true)
        UpdateApkVerifier.verify(context, target, release)
        inst.runOnMainSync {
            UpdateManager.reset()
            // 仅测试 APK 反射注入已经过实际校验的本地夹具；正式应用没有调试 intent 或地址覆盖入口。
            UpdateManager::class.java.getDeclaredField("state").apply { isAccessible = true }.set(null, UpdateState("ready", "本地校验通过", release))
        }
        return release
    }
    @Test fun rejectsWrongCertificateVersionAndCorruption() {
        val release = prepare()
        try {
            assertTrue(runCatching { UpdateApkVerifier.verify(context, UpdateManager.apk(context), release.copy(certificate = "0".repeat(64))) }.isFailure)
            assertTrue(runCatching { UpdateApkVerifier.verify(context, UpdateManager.apk(context), release.copy(code = release.code + 1)) }.isFailure)
            UpdateManager.apk(context).appendBytes(byteArrayOf(42))
            assertTrue(runCatching { UpdateApkVerifier.verify(context, UpdateManager.apk(context), release) }.isFailure)
        } finally { inst.runOnMainSync { UpdateManager.reset() }; UpdateManager.apk(context).delete() }
    }
    @Test fun cancelSystemInstallThenRetry() {
        val before = UpdateApkVerifier.code(UpdateApkVerifier.installed(context))
        val settings = Preferences.read(context).toString()
        prepare()
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        context.startActivity(Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("前往安装")), 10000)!!.click()
        val installer = Pattern.compile(".*packageinstaller")
        val cancel = device.wait(Until.findObject(By.pkg(installer).text(Pattern.compile("(?i)cancel|取消"))), 15000)
        assertNotNull("必须看到 Android 系统安装确认", cancel)
        cancel!!.click()
        val returned = device.wait(Until.hasObject(By.text("已取消安装，当前版本和数据保持不变")), 10000)
        assertTrue("安装取消状态：${UpdateManager.state}", returned)
        assertEquals(before, UpdateApkVerifier.code(UpdateApkVerifier.installed(context)))
        assertEquals(settings, Preferences.read(context).toString())
        device.findObject(By.text("前往安装")).click()
        assertNotNull(device.wait(Until.findObject(By.pkg(installer).text(Pattern.compile("(?i)update|install|更新|安装"))), 15000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "update-system-confirm.png"))
        // 将二次确认留给外部驱动点击；包替换会终止目标进程，后置断言由驱动完成。
    }
}
