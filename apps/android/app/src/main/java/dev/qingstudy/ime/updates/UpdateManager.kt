//! 主应用进程的单任务更新器；后台线程不触碰 IME、模型及学习数据。
package dev.qingstudy.ime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object UpdateManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val transport = UpdateTransport()
    private var cancelled = AtomicBoolean(false)
    private val listeners = mutableSetOf<(UpdateState) -> Unit>()
    var state = UpdateState(); private set
    val busy get() = state.phase in listOf("checking", "downloading", "verifying")
    fun observe(listener: (UpdateState) -> Unit) { listeners.add(listener); listener(state) }
    fun forget(listener: (UpdateState) -> Unit) { listeners.remove(listener) }
    private fun publish(next: UpdateState) { state = next; listeners.toList().forEach { it(next) } }
    private fun later(token: AtomicBoolean, next: UpdateState) { main.post { if (!token.get()) publish(next) } }
    fun reset() { cancel(); publish(UpdateState()) }
    fun cancel() {
        cancelled.set(true); transport.cancel()
        if (busy) publish(UpdateState(if (state.release == null) "idle" else "new", "已取消，当前版本不受影响", state.release))
    }
    fun apk(context: Context) = File(context.cacheDir, "updates/ready.apk")
    fun autoCheck(context: Context) {
        val prefs = UpdatePreferences.store(context)
        if (!prefs.getBoolean("auto", false) || busy || state.phase == "ready") return
        val last = prefs.getLong("lastAttempt", 0)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        check(context)
    }
    fun check(context: Context) {
        if (busy) return
        val app = context.applicationContext
        val token = AtomicBoolean(false); cancelled = token
        val prefs = UpdatePreferences.store(app)
        val channel = prefs.getString("channel", "beta") ?: "beta"
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        publish(UpdateState("checking", "正在连接发布源并验证版本清单…"))
        executor.execute {
            try {
                val key = app.assets.open("updates/public-key.txt").bufferedReader().use { it.readText().trim() }
                val releases = UpdateIndex.verified(transport.index(token), key)
                val release = UpdateIndex.choose(releases, channel, UpdateApkVerifier.code(UpdateApkVerifier.installed(app)), Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
                later(token, if (release == null) UpdateState("current", if (releases.isEmpty()) "发布源已连接，尚无可供下载的正式版本" else "当前渠道暂无适合此设备的新版本")
                    else if (setOf(release.certificate) != UpdateApkVerifier.certificates(UpdateApkVerifier.installed(app)))
                        UpdateState("incompatible", "新版使用不同的安装证书，不能直接覆盖。已保留当前版本和数据，请等待迁移说明。")
                    else UpdateState("new", "发现 ${release.name}", release))
                if (!token.get()) prefs.edit().putLong("lastSuccess", System.currentTimeMillis()).apply()
            } catch (error: Exception) { later(token, UpdateState("error", error.message ?: "无法连接发布源，请稍后重试")) }
        }
    }
    fun download(context: Context, mobileOnce: Boolean) {
        val release = state.release ?: return
        if (busy) return
        val app = context.applicationContext
        val token = AtomicBoolean(false); cancelled = token
        val wifiOnly = UpdatePreferences.store(app).getBoolean("wifi", true)
        publish(UpdateState("downloading", "下载中，可随时取消", release))
        executor.execute {
            val file = apk(app)
            try {
                file.parentFile!!.mkdirs(); file.delete()
                var last = -1
                transport.read(release.url, token) { input ->
                    UpdateDownload.copy(input, file, release, token,
                        { UpdatePreferences.network(app) && (!wifiOnly || mobileOnce || UpdatePreferences.wifi(app)) }) { percent ->
                        if (last != percent) { last = percent; later(token, UpdateState("downloading", "已下载 $percent%", release, percent)) }
                    }
                }
                later(token, UpdateState("verifying", "核对文件、包名、签名与版本…", release))
                UpdateApkVerifier.verify(app, file, release)
                if (token.get()) file.delete() else later(token, UpdateState("ready", "校验通过，安装前仍需系统确认", release))
            } catch (error: Exception) {
                file.delete()
                later(token, UpdateState("failed", error.message ?: "下载或校验失败，请重试", release))
            }
        }
    }
    fun prepareInstall(context: Context, ready: (File, UpdateRelease) -> Unit) {
        val release = state.release ?: return
        if (state.phase != "ready") return
        val app = context.applicationContext
        val token = AtomicBoolean(false); cancelled = token
        publish(UpdateState("verifying", "安装前重新核对文件…", release))
        executor.execute {
            try {
                UpdateApkVerifier.verify(app, apk(app), release)
                main.post { if (!token.get()) { publish(UpdateState("ready", "等待系统安装确认", release)); ready(apk(app), release) } }
            } catch (error: Exception) { apk(app).delete(); later(token, UpdateState("failed", error.message ?: "校验失败", release)) }
        }
    }
    fun installResult(message: String) { publish(state.copy(message = message)) }
}
