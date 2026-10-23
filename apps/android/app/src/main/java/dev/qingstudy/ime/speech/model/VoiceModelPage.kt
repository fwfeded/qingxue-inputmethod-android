//! 用户主动下载/暂停/删除离线包；页面离开即停止网络任务，保留可续传片段。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoiceModelPage(private val activity: Activity, private val back: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val canceled = AtomicBoolean(false)
    private var closed = false
    private var busy = false
    private var deleting = false
    private var message = ""
    private var percent = 0
    private var status: TextView? = null
    private var progress: ProgressBar? = null

    fun show() {
        if (closed) return
        val ui = SettingsUi(activity)
        val ready = VoiceModels.isReady(activity)
        ui.section("离线语音包")
        ui.label("SenseVoice int8 + Silero VAD", 20f, false)
        ui.label("${VoiceModels.totalBytes / 1024 / 1024} MiB · 中文等模型支持语言。选用离线语音时，录音只在本机识别。")
        ui.label("模型来自 sherpa-onnx 的 Hugging Face 仓库及 GitHub 发布源，完成后校验文件。基础安装包不内置模型；若连接失败，可能是当前网络无法访问这些源。", 12f)
        status = TextView(activity).apply {
            text = message.ifEmpty { if (ready) "已就绪" else "尚未下载" }
            contentDescription = "离线包状态"; setTextColor(ui.palette.ink); textSize = 15f
        }.also { ui.box.addView(it) }
        if (busy) {
            progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = percent; contentDescription = "离线包下载进度"
            }.also { ui.box.addView(it) }
            if (!deleting) {
                ui.button("暂停下载") { pause() }
                ui.label("离开页面会暂停；再次下载会复用已下载片段。", 12f)
            }
        } else if (!ready) {
            ui.button(if (message.isEmpty()) "立即下载" else "重试／继续下载", true) { download(false) }
            ui.button("Wi-Fi 下载") { download(true) }
            ui.label("立即下载可能使用移动流量；Wi-Fi下载会等待无线网络。", 12f)
            if (VoiceModels.hasPartial(activity)) ui.button("删除已下载片段") { confirmDelete(false) }
        } else ui.button("删除离线语音包") {
            confirmDelete(true)
        }
        ui.show("离线语音包", back)
    }

    private fun download(wifi: Boolean) {
        if (busy || closed) return
        busy = true; canceled.set(false); message = if (wifi) "检查Wi-Fi…" else "连接下载源…"; show()
        worker.execute {
            var lastPercent = -1
            var lastMessage = ""
            val result = runCatching { VoiceModels.download(activity, wifi, { canceled.get() }) { p, text ->
                if (p != lastPercent || text != lastMessage) {
                    lastPercent = p; lastMessage = text
                    main.post { if (!closed) { percent = p; message = text; status?.text = text; progress?.progress = p } }
                }
            } }
            main.post {
                busy = false
                message = result.fold({ "校验完成，可以离线使用" }, { if (canceled.get()) "已暂停，片段已保留" else "下载失败：${it.message}" })
                if (!closed) show()
            }
        }
    }
    private fun confirmDelete(complete: Boolean) {
        AlertDialog.Builder(activity).setTitle(if (complete) "删除离线语音包？" else "删除已下载片段？")
            .setMessage(if (complete) "会释放语音资源并删除本机模型；拼音、英文释义和学习数据保留。需要时可重新下载。"
                else "清理未启用的下载文件；已安装模型和学习数据保留。")
            .setPositiveButton("删除") { _, _ -> delete(complete) }.setNegativeButton("取消", null).show()
    }
    private fun delete(complete: Boolean) {
        if (busy || closed) return
        busy = true; deleting = true; message = "正在释放并删除模型…"; show()
        val client = if (complete) VoiceClient(activity, EmptyVoiceListener()).also { it.release() } else null
        worker.execute {
            val result = runCatching { if (complete) VoiceModels.delete(activity) else VoiceModels.deletePartial(activity) }
            main.post {
                client?.close(); busy = false; deleting = false; percent = 0
                message = result.fold({ "已删除，可重新下载" }, { "删除失败：${it.message}" })
                if (!closed) show()
            }
        }
    }
    fun pause() { if (busy && !deleting) { canceled.set(true); status?.text = "正在暂停…" } }
    override fun close() { closed = true; canceled.set(true); worker.shutdown(); status = null; progress = null }
}
