package dev.qingstudy.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import java.io.File

// 只管理平台会话和 composing 范围。格式/口令由 Rust 处理，提供方由用户选择。
class VoiceController(
    private val ime: InputMethodService,
    private val keyboard: () -> KeyboardView?,
    private val alternatives: (String, (List<Pair<String, String>>) -> Unit) -> Unit,
    private val enter: () -> Unit,
    private val learnCorrection: (String, String, String) -> Unit = { _, _, _ -> }
) : VoiceClient.Listener {
    private var offline: VoiceClient? = null
    private var system: SystemSpeechClient? = null
    private var online: OnlineSpeechClient? = null
    private var provider = "system"
    private var recognitionGeneration = 0L
    private var connection: InputConnection? = null
    private var original = ""
    private var base = ""
    private var text = ""
    private val history = java.util.ArrayDeque<String>()
    private var held = false
    private var running = false
    private var panel = false
    private var finishAction: String? = null
    private var ownComposition = false
    private var startOffset = -1
    private var originalEnd = -1
    private var state = "idle"
    private var message = ""
    private var actionDone = false
    private var writing = false
    private var commandSend = false
    private var editorSelection = ""
    private var sessionActive = false
    private var correctionSource: String? = null
    private var correctionId = ""
    private var correctionConfirmed = false
    private var textReview: TextVoiceReview? = null
    private var selectionGeneration = 0
    private var cursorStart = -1
    private var cursorEnd = -1
    private val deferredInput = java.util.ArrayDeque<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val finalTimeout = Runnable {
        if (finishAction != null) {
            correctionConfirmed = false
            cancelRecognition(); running = false; finishAction = "finish"
            android.widget.Toast.makeText(ime,"语音未及时完成，已保留已识别文字",android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    private val prepareOffline = Runnable {
        if (!sessionActive && ime.isInputViewShown && VoiceSettings.read(ime).optString("provider","system")=="offline" && VoiceModels.isReady(ime)) {
            val manager=ime.getSystemService(android.app.ActivityManager::class.java)
            val memory=android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            if(!manager.isLowRamDevice&&!memory.lowMemory&&memory.availMem>512L*1024*1024) {
                val client=offline ?: VoiceClient(ime,sessionListener(recognitionGeneration)).also { offline=it }
                client.warmUp()
            }
        }
    }
    val busy: Boolean get() = sessionActive

    fun editorStarted(info: android.view.inputmethod.EditorInfo) { cursorStart=info.initialSelStart;cursorEnd=info.initialSelEnd }
    fun prepareVisibleKeyboard() { main.removeCallbacks(prepareOffline);main.postDelayed(prepareOffline,800) }
    fun deferUntilFinal(input: () -> Unit): Boolean {
        if(finishAction==null||panel)return false
        if (provider == "online") { settlePendingInput(); return false }
        deferredInput.addLast(input)
        if(deferredInput.size>=64)finalTimeout.run()
        return true
    }

    // 用户主动收起或打开设置时，先把已按下的键交还当前编辑器；焦点切换仍走 cancel。
    fun settlePendingInput() {
        if(finishAction==null)return
        correctionConfirmed = false
        cancelRecognition();running=false;finishAction="finish"
        finish()
    }

    fun start(hold: Boolean) {
        main.removeCallbacks(prepareOffline)
        if(finishAction!=null) {
            if (deferUntilFinal { if(!hold || keyboard()?.voiceHoldActive==true) start(hold) }) return
        }
        selectionGeneration++
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ime.startActivity(Intent(ime, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        val info = ime.currentInputEditorInfo ?: return
        // 旧录音不能写入后来获得焦点的编辑器。
        if (!sessionActive) {
            connection = ime.currentInputConnection ?: return
            startOffset = minOf(cursorStart,cursorEnd)
            originalEnd = maxOf(cursorStart,cursorEnd)
            // onUpdateSelection 持续维护选区，普通光标不向宿主索取整段文本。
            original = if(startOffset>=0&&originalEnd>startOffset)connection?.getSelectedText(0)?.toString().orEmpty() else ""
            text = ""; base = ""; history.clear(); ownComposition = false; actionDone = false
        }
        remember()
        // 继续录音可能追加或自动整理文本；须重新手工编辑后才能产生学习事件。
        correctionSource = null; correctionConfirmed = false
        correctionId = java.util.UUID.randomUUID().toString()
        sessionActive = true; held = hold; panel = true; finishAction = null; editorSelection = ""; commandSend = false
        val settings = VoiceSettings.read(ime)
        // 标记位于应用私有目录，发布包忽略；离线服务还会核验 IPC 调用 UID。
        val fixture = BuildConfig.DEBUG && File(ime.filesDir, "debug-voice-enabled").isFile
        provider = if (fixture) "offline" else settings.optString("provider", "system")
        recognitionGeneration++
        // 同源继续保留绑定，交给服务原有空闲回收；切换来源才解除离线连接。
        if (provider == "offline") offline?.cancel() else { offline?.close(); offline = null }
        system?.close(); system = null
        online?.close(); online = null
        base = text; running = true; state = "loading"
        message = if (provider == "offline") "正在准备离线语音…" else if (provider == "online") "正在连接我的电脑…" else "正在连接手机语音服务…"
        keyboard()?.showVoice(held, EditorAction.label(info))
        keyboard()?.voiceLevel(0f)
        render()
        val listener = sessionListener(recognitionGeneration)
        if (provider == "offline") {
            if (!VoiceModels.isReady(ime)) {
                onState("error", "请先在语音设置中下载离线语音包")
                return
            }
            val client = offline ?: VoiceClient(ime, listener).also { offline = it }
            if (fixture) client.startFixtureForTest(listener) else client.start(settings.optBoolean("bluetooth"), listener)
        } else if (provider == "online") {
            val onlineFixture = BuildConfig.DEBUG && File(ime.filesDir, "debug-online-enabled").isFile
            online = OnlineSpeechClient(ime, listener).also { it.start(settings.optBoolean("bluetooth"), onlineFixture) }
        } else {
            system = SystemSpeechClient(ime, listener).also { it.start(settings.optString("punctuation", "all")) }
        }
    }
    fun command(action: String, argument: String = "") {
        if (!sessionActive) return
        when (action) {
            "organize" -> {
                if (running || text.isBlank()) return
                // 已结束识别，不让语音和文字权重同时常驻；继续说话时按原路径重新准备。
                offline?.release()
                textReview?.close()
                textReview = TextVoiceReview(ime, text, models = {
                    val source = text
                    cancel(false)
                    ime.startActivity(Intent(ime, TextToolsActivity::class.java).putExtra("source", source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { adopted ->
                    if (sessionActive) {
                        remember(); text = adopted; base = text
                        correctionSource = null; correctionConfirmed = false
                        write(); render()
                    }
                }
            }
            "selection" -> {
                val range = argument.split(':').mapNotNull { it.toIntOrNull() }
                val generation = ++selectionGeneration
                keyboard()?.voiceCandidates(emptyList())
                if (range.size != 2 || range.any { it !in 0..text.length } || range[0] == range[1]) { keyboard()?.voiceCandidates(emptyList()); return }
                if (running) { cancelRecognition(); running = false; state = "idle"; base = text; render() }
                val word = text.substring(minOf(range[0], range[1]), maxOf(range[0], range[1]))
                alternatives(word) { items -> if (sessionActive && generation == selectionGeneration) keyboard()?.voiceCandidates(items) }
            }
            "server-settings" -> { cancel(false); ime.startActivity(Intent(ime, VoiceSettingsActivity::class.java).putExtra("open_voice_server", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "use-offline" -> {
                cancel(false)
                if (VoiceModels.isReady(ime)) { VoiceSettings.save(ime, "provider", "offline"); start(false) }
                else ime.startActivity(Intent(ime, VoiceSettingsActivity::class.java).putExtra("open_voice_models", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "cancel" -> cancel(true)
            "interrupt" -> cancel(false)
            "finish", "send", "keyboard" -> {
                if(finishAction!=null)return
                correctionConfirmed = action in listOf("finish", "send") && correctionSource != null
                finishAction = action; editorSelection = argument
                panel=false;keyboard()?.hideVoice()
                if (running) {
                    state = "processing"; message = "正在识别…"
                    main.postDelayed(finalTimeout,8000);stopRecognition()
                } else finish()
            }
            "pause" -> if (running) { state = "processing"; message = "正在识别…"; stopRecognition(); render() } else start(false)
            "edit" -> {
                selectionGeneration++
                if (running) { cancelRecognition(); running = false; state = "idle" }
                if (argument != text && correctionSource == null) correctionSource = text
                remember(); text = argument; base = text; write(); render()
            }
            "undo" -> {
                cancelRecognition(); running = false; state = "idle"
                if (!history.peekLast().isNullOrEmpty()) { text = history.removeLast(); base = text; write(); render() }
                else cancel(true)
            }
        }
    }
    override fun onState(state: String, message: String) {
        if (!sessionActive) return
        this.state = state; this.message = message
        if (state == "error" || state == "idle") {
            if(finishAction!=null) {
                correctionConfirmed = false
                running=false;finishAction="finish"
                if(state=="error")android.widget.Toast.makeText(ime,message,android.widget.Toast.LENGTH_SHORT).show()
                finish();return
            }
            running = false; finishAction = null; held = false
            keyboard()?.voiceLevel(0f)
            keyboard()?.showVoice(false, EditorAction.label(ime.currentInputEditorInfo))
        }
        render()
        if (state == "error" && provider == "online") keyboard()?.voiceRecovery()
    }
    override fun onPartial(text: String) { accept(text, false) }
    override fun onResult(text: String, decodeMs: Long) {
        if (!sessionActive) return
        running = false; accept(text, true); state = "idle"
        keyboard()?.voiceLevel(0f)
        message = if (text.isBlank()) "没听清，可继续说话" else if (provider == "offline") "已在本机识别 · ${decodeMs}ms" else if (provider == "online") "已由电脑校正 · ${decodeMs}ms" else "已由手机语音服务识别"
        if (commandSend && finishAction != "cancel") finishAction = "send"
        if (finishAction != null) finish() else { if (!held) keyboard()?.showVoice(false, EditorAction.label(ime.currentInputEditorInfo)); render() }
    }
    override fun onMetrics(loadMs: Long, reused: Boolean) {
        android.util.Log.i("QingVoiceUI", "loadMs=$loadMs reused=$reused")
    }
    override fun onLevel(level: Float) { if (sessionActive) keyboard()?.voiceLevel(level) }
    private fun accept(raw: String, final: Boolean) {
        if (!sessionActive) return
        val formatted = runCatching { VoiceSettings.format(ime, raw, final) }.getOrElse { raw to false }
        text = base + formatted.first; commandSend = final && formatted.second
        write(); render()
    }
    private fun write() {
        val target = connection ?: return
        // 未识别出文字时保留宿主原选区。
        if (text.isEmpty() && !ownComposition) return
        writing = true
        try { if (target.setComposingText(text, 1)) ownComposition = true }
        finally { writing = false }
    }
    private fun finish() {
        val action = finishAction ?: return
        main.removeCallbacks(finalTimeout)
        finishAction = null
        val generation=recognitionGeneration
        val inputs=deferredInput.toList();deferredInput.clear()
        val nextHold = !panel && keyboard()?.voiceHoldActive==true
        // 先结束状态，宿主同步回调选区或焦点不能重复执行发送。
        running=false;sessionActive=false;panel=false
        val committed = if (ownComposition) connection?.finishComposingText() == true else false
        val source = correctionSource
        if (committed && correctionConfirmed && source != null && source != text && connection === ime.currentInputConnection) {
            learnCorrection(correctionId, source, text)
        }
        correctionSource = null; correctionConfirmed = false
        if (action == "send" && !actionDone && text.isNotBlank()) {
            actionDone = true; ownComposition = false
            enter()
        }
        if (action == "keyboard" && startOffset >= 0 && editorSelection.contains(':')) {
            val parts = editorSelection.split(':').mapNotNull { it.toIntOrNull() }
            if (parts.size == 2 && parts.all { it in 0..text.length }) connection?.setSelection(startOffset + parts[0], startOffset + parts[1])
        }
        ownComposition = false; running = false; sessionActive = false; panel = false
        keyboard()?.voiceLevel(0f)
        keyboard()?.hideVoice(nextHold)
        inputs.forEach { if(generation==recognitionGeneration)it() }
    }
    fun cancel(restore: Boolean) {
        textReview?.close(); textReview = null
        correctionSource = null; correctionConfirmed = false
        main.removeCallbacks(prepareOffline);main.removeCallbacks(finalTimeout);deferredInput.clear()
        selectionGeneration++
        cancelRecognition(); running = false; finishAction = null
        if (restore && ownComposition) { connection?.setComposingText(original, 1); connection?.finishComposingText() }
        else if (ownComposition) connection?.finishComposingText()
        ownComposition = false; sessionActive = false; panel = false; text = ""; base = ""; connection = null
        keyboard()?.hideVoice()
    }
    fun selectionChanged(newStart: Int, newEnd: Int, composingEnd: Int) {
        cursorStart=newStart;cursorEnd=newEnd
        if (writing || !sessionActive) return
        if (ownComposition) {
            val end = if (text.isEmpty() && composingEnd < 0) startOffset else composingEnd
            if (newStart != end || newEnd != end) cancel(false)
        } else if (startOffset >= 0 && (minOf(newStart, newEnd) != startOffset || maxOf(newStart, newEnd) != originalEnd)) cancel(false)
    }
    fun close() { cancel(false); offline?.close(); offline = null; system?.close(); system = null; online?.close(); online = null }
    private fun remember() { if (history.peekLast() != text) history.addLast(text); while (history.size > 40) history.removeFirst() }
    private fun render() { if (panel) keyboard()?.updateVoice(text, message, running, running && state == "processing") }
    private fun stopRecognition() {
        keyboard()?.voiceLevel(0f)
        when (provider) { "offline" -> offline?.stop(); "online" -> online?.stop(); else -> system?.stop() }
    }
    private fun cancelRecognition() {
        recognitionGeneration++
        offline?.cancel(); system?.cancel(); online?.cancel()
        keyboard()?.voiceLevel(0f)
    }
    private fun sessionListener(generation: Long) = object : VoiceClient.Listener {
        private fun valid() = sessionActive && recognitionGeneration == generation
        override fun onState(state: String, message: String) { if (valid()) this@VoiceController.onState(state, message) }
        override fun onPartial(text: String) { if (valid()) this@VoiceController.onPartial(text) }
        override fun onResult(text: String, decodeMs: Long) { if (valid()) this@VoiceController.onResult(text, decodeMs) }
        override fun onMetrics(loadMs: Long, reused: Boolean) { if (valid()) this@VoiceController.onMetrics(loadMs, reused) }
        override fun onLevel(level: Float) { if (valid()) this@VoiceController.onLevel(level) }
    }
}
