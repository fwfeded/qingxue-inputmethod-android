//! 已确认下载页：进度、暂停续传、失败重试和删除；离开页面暂停网络读取。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TextModelActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val canceled = AtomicBoolean(false)
    private var busy = false
    private var closed = false
    private var message = ""
    private var alternate = false
    private var percent = 0
    private var label: TextView? = null
    private var bar: ProgressBar? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); show() }
    override fun onStop() { canceled.set(true); super.onStop() }
    override fun onDestroy() { closed = true; canceled.set(true); worker.shutdown(); super.onDestroy() }
    private fun show() {
        if (closed) return
        val ui = SettingsUi(this)
        ui.section("离线文字包")
        ui.label("Qwen2.5 1.5B · Q4_K_M", 20f, false)
        ui.label("1065.6 MiB · 按需下载，不包含在安装包内。下载后整理仅在本机完成，与离线语音包分开管理。")
        ui.label("来源：Qwen 官方公开仓库。模型固定 SHA-256 校验；请预留约 1.2 GB 存储和足够运行内存。", 12f)
        label = ui.label(message.ifEmpty { if (TextModels.ready(this)) "已就绪 · 可断网整理" else "尚未下载 · 已下载 ${TextModels.partial(this) / 1024 / 1024} MiB" }, 16f, false).apply { contentDescription = "文字包状态" }
        if (busy) {
            bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = percent; contentDescription = "文字包下载进度" }.also { ui.box.addView(it) }
            ui.button("暂停") { canceled.set(true); label?.text = "正在暂停，片段会保留…" }
        } else {
            if (!TextModels.ready(this)) {
                ui.button(if (TextModels.partial(this) > 0) "继续下载" else "下载文字包", true) { download() }
                ui.toggle("备用下载源", if (alternate) "Hugging Face 官方固定版本" else "ModelScope Qwen 官方仓库；两源使用同一散列。", alternate) { alternate = it; show() }
                ui.label("点击下载可能使用移动流量，建议连接 Wi-Fi。离开页面自动暂停，再进入可续传。", 12f)
            }
            if (TextModels.ready(this) || TextModels.partial(this) > 0) ui.button("删除文字包") {
                AlertDialog.Builder(this).setTitle("删除文字包？").setMessage("仅删除文字模型和下载片段，保留语音包、英文释义、词库和学习数据。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> runJob("等待整理结束并删除…") { TextModels.delete(this) { canceled.get() }; "已删除文字包" } }.show()
            }
        }
        ui.button("来源与许可") {
            val notice = assets.open("licenses/text/models.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("文字模型与运行时").setMessage(notice).setPositiveButton("关闭", null).show()
        }
        ui.show("离线文字包") { finish() }
    }
    private fun download() = runJob("连接下载源…") {
        var last = -1
        TextModels.download(this, alternate, { canceled.get() }) { p ->
            if (p != last) { last = p; main.post { if (!closed) { percent = p; bar?.progress = p; label?.text = if (p == 100) "下载完成，正在校验…" else "已下载 $p%" } } }
        }
        "校验完成 · 可断网整理"
    }
    private fun runJob(status: String, work: () -> String) {
        if (busy) return
        busy = true; canceled.set(false); message = status; show()
        worker.execute {
            val result = runCatching(work)
            main.post { busy = false; message = result.getOrElse { if (canceled.get()) "已暂停，片段保留" else "未完成：${it.message}" }; show() }
        }
    }
}
