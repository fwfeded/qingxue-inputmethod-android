//! 沿用已确认更新预览：手动/可选自动检查、渠道、取消下载及系统确认。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class UpdateActivity : Activity() {
    private lateinit var ui: SettingsUi
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var action: Button
    private lateinit var secondary: Button
    private lateinit var channel: Button
    private lateinit var progress: ProgressBar
    private val listener: (UpdateState) -> Unit = { render(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SettingsUi(this)
        val prefs = UpdatePreferences.store(this)
        ui.label("青学 ${BuildConfig.VERSION_NAME}", 22f, false)
        ui.label("只查询版本信息，不发送输入内容、录音或学习数据。", 13f)
        status = ui.label("", 19f, false).apply { contentDescription = "更新状态" }
        notes = ui.label("", 14f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        ui.box.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(12)))
        action = ui.button("检查更新", true, ::action)
        secondary = ui.button("取消下载") {
            if (UpdateManager.busy) UpdateManager.cancel() else finish()
        }
        ui.section("更新偏好")
        ui.toggle("自动检查更新", "默认关闭；开启后，在打开首页时最多每天检查一次，不自动下载或安装。", prefs.getBoolean("auto", false)) {
            prefs.edit().putBoolean("auto", it).apply()
        }
        ui.toggle("仅在 Wi-Fi 下下载", "移动网络或按流量计费的 Wi-Fi 会再次询问；网络变化时停止下载。", prefs.getBoolean("wifi", true)) {
            prefs.edit().putBoolean("wifi", it).apply()
        }
        channel = ui.button("更新渠道") {
            val selected = prefs.getString("channel", "beta") ?: "beta"
            AlertDialog.Builder(this).setTitle("更新渠道")
                .setSingleChoiceItems(arrayOf("稳定版", "测试版（含稳定版）"), if (selected == "stable") 0 else 1) { dialog, which ->
                    prefs.edit().putString("channel", if (which == 0) "stable" else "beta").apply()
                    UpdateManager.reset(); dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        ui.row("GitHub 开源仓库", "源码、变更记录与发布信息", "↗") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateIndex.REPOSITORY))) }
                .onFailure { AlertDialog.Builder(this).setMessage("未找到浏览器：${UpdateIndex.REPOSITORY}").setPositiveButton("知道了", null).show() }
        }
        ui.label("语音和文字模型单独管理。应用更新不会主动删除词库、模型或学习记录。", 12f)
        ui.show("应用更新", ::finish)
    }
    override fun onStart() { super.onStart(); UpdateManager.observe(listener) }
    override fun onStop() { UpdateManager.forget(listener); super.onStop() }
    override fun onResume() {
        super.onResume()
        val prefs = UpdatePreferences.store(this)
        val target = prefs.getLong("pendingVersion", 0)
        if (target > 0 && UpdateApkVerifier.code(UpdateApkVerifier.installed(this)) >= target) {
            prefs.edit().remove("pendingVersion").apply()
            UpdateManager.installResult("已更新至 ${BuildConfig.VERSION_NAME}")
        }
    }
    private fun render(state: UpdateState) {
        status.text = state.message
        notes.text = state.release?.let { "${it.name} · ${it.abi} · %.1f MiB\n%s".format(it.bytes / 1048576.0, it.notes) } ?: ""
        notes.visibility = if (state.release == null) View.GONE else View.VISIBLE
        progress.visibility = if (state.phase == "downloading") View.VISIBLE else View.GONE
        progress.progress = state.percent
        action.text = when (state.phase) {
            "checking" -> "正在检查…"; "downloading" -> "正在下载…"; "verifying" -> "正在校验…"
            "ready" -> "前往安装"; "new", "failed" -> "下载更新"; else -> "检查更新"
        }
        action.isEnabled = !UpdateManager.busy
        secondary.visibility = if (UpdateManager.busy || state.phase == "ready") View.VISIBLE else View.GONE
        secondary.text = if (UpdateManager.busy) "取消" else "稍后安装"
        channel.isEnabled = !UpdateManager.busy
        channel.text = if (UpdatePreferences.store(this).getString("channel", "beta") == "stable") "更新渠道 · 稳定版" else "更新渠道 · 测试版"
    }
    private fun action() {
        when (UpdateManager.state.phase) {
            "new", "failed" -> {
                if (UpdatePreferences.store(this).getBoolean("wifi", true) && !UpdatePreferences.wifi(this)) {
                    AlertDialog.Builder(this).setTitle("当前不是免费 Wi-Fi")
                        .setMessage("本次下载可能产生流量费用。继续仅对本次生效，不改变 Wi-Fi 设置。")
                        .setNegativeButton("取消", null).setPositiveButton("本次继续下载") { _, _ -> UpdateManager.download(this, true) }.show()
                } else UpdateManager.download(this, false)
            }
            "ready" -> install()
            else -> UpdateManager.check(this)
        }
    }
    @Suppress("DEPRECATION")
    private fun install() {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("允许安装应用更新")
                .setMessage("需要在系统中允许青学安装更新。授权后返回此页，再点“前往安装”；仍需系统确认。")
                .setNegativeButton("取消", null).setPositiveButton("前往设置") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { UpdateManager.installResult("无法打开系统安装权限，请在系统设置中检查") }
                }.show()
            return
        }
        UpdateManager.prepareInstall(this) { _, release ->
            if (isFinishing || isDestroyed) return@prepareInstall
            UpdatePreferences.store(this).edit().putLong("pendingVersion", release.code).apply()
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.parse("content://$packageName.updates/update.apk"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true), 701)
            }.onFailure { UpdateManager.installResult("无法打开系统安装器，当前版本已保留") }
        }
    }
    @Deprecated("系统安装器返回结果")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            val target = UpdatePreferences.store(this).getLong("pendingVersion", Long.MAX_VALUE)
            if (UpdateApkVerifier.code(UpdateApkVerifier.installed(this)) >= target) UpdateManager.installResult("更新已安装")
            else UpdateManager.installResult(if (resultCode == RESULT_CANCELED) "已取消安装，当前版本和数据保持不变" else "系统未完成安装，当前版本已保留")
            UpdatePreferences.store(this).edit().remove("pendingVersion").apply()
        }
    }
}
