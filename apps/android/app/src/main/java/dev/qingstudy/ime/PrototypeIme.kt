// 系统输入法壳：串行处理按键，用会话代号隔离焦点切换后的异步结果。
package dev.qingstudy.ime

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class PrototypeIme : InputMethodService() {
    private lateinit var engine: EngineWorker
    private lateinit var voice: VoiceController
    private var keyboard: KeyboardView? = null
    private var ready = false
    private var loading = true
    private var failure: String? = null
    private var epoch = 0
    private var pending = 0
    private var composing = ""
    private var direct = false
    private var numberLayout = false
    private var active = false
    private var lastFrame = Frame("", emptyList(), "", false)
    private var selectionStart = -1
    private var selectionEnd = -1
    private var expectedSelection: Int? = null
    // 按下和抬起必须成对消费；true 表示排队后原样转交给目标编辑器。
    private val hardwareKeys = mutableMapOf<Pair<Int, Int>, Boolean>()

    override fun onCreate() {
        super.onCreate()
        voice = VoiceController(this, { keyboard }, { word, callback -> engine.voiceAlternatives(word, callback) }, ::enter) { id, original, edited ->
            engine.step("voice-learn", org.json.JSONObject().put("id", id).put("original", original).put("edited", edited).toString()) { result ->
                result.onSuccess { frame -> if (frame.learnedVoiceWords > 0) {
                    android.widget.Toast.makeText(this, "已记住本次纠正中的 ${frame.learnedVoiceWords} 个词", android.widget.Toast.LENGTH_SHORT).show()
                } }
            }
        }
        engine = EngineWorker(this) { error ->
            failure = error
            loading = false
            ready = error == null
            if (ready) keyboard?.let { view -> view.render(lastFrame); view.setPending(pending != 0) }
            else keyboard?.status("加载失败：$error；请切换输入法", false)
        }
    }
    override fun onCreateInputView(): View = KeyboardView(this, ::key).also {
        keyboard = it
        it.editor(currentInputEditorInfo)
        it.setDirectLayout(direct)
        it.setNumberLayout(numberLayout)
        if (ready) it.render(lastFrame)
        else failure?.let { error -> it.status("加载失败：$error", false) }
    }
    override fun onWindowShown() {
        super.onWindowShown()
        if(active&&!direct)voice.prepareVisibleKeyboard()
        // 冷启动首次显示后再测量一次导航栏留白，避免初始末行落在系统导航栏后。
        keyboard?.let { view -> view.post {
            if (view.isAttachedToWindow) { view.requestApplyInsets(); view.requestLayout() }
        } }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onWindowHidden() { voice.cancel(false); keyboard?.cancelGestures(); super.onWindowHidden() }
    // 实体键盘连接时仍需让用户看到中文候选与英文释义。
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 返回键交给 InputMethodService 收起键盘，不能转发成目标 Activity 的返回操作。
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // Worker 会把操作排在词库加载之后；启动期也消费硬件按键，避免原始拼音泄入编辑器。
        if ((!ready && !loading) || !active || direct) return super.onKeyDown(keyCode, event)
        val deferredDown=KeyEvent(event)
        if(voice.deferUntilFinal { onKeyDown(keyCode,deferredDown) })return true
        val identity = event.deviceId to keyCode
        val shortcut = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        val character = event.unicodeChar
        if (!shortcut && (pending > 0 || composing.isNotEmpty())) {
            val digit = character - '0'.code
            if (digit in 1..9) {
                val index = keyboard?.candidateIndex(digit) ?: (digit - 1)
                hardwareKeys[identity] = false
                submit("digit", index.toString())
                return true
            }
        }
        val action = if (shortcut) null else when {
            keyCode == KeyEvent.KEYCODE_ESCAPE -> "cancel"
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> "previous"
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> "next"
            keyCode == KeyEvent.KEYCODE_TAB && (pending > 0 || composing.isNotEmpty()) -> "space"
            character == ';'.code && lastFrame.scheme in listOf("微软双拼", "搜狗双拼") -> ";"
            lastFrame.english && character in 'A'.code..'Z'.code -> character.toChar().toString()
            character in 'a'.code..'z'.code -> character.toChar().toString()
            character == '\''.code -> "'"
            keyCode == KeyEvent.KEYCODE_SPACE -> "space"
            keyCode == KeyEvent.KEYCODE_DEL -> "delete"
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            else -> null
        }
        if (action != null) {
            hardwareKeys[identity] = false
            key(action, "")
            return true
        }
        if (!shortcut && character in 33..126) {
            hardwareKeys[identity] = false
            submit("graphic", character.toChar().toString())
            return true
        }
        if (pending > 0 || composing.isNotEmpty() || hardwareKeys[identity] == true) {
            // 标点、方向键及快捷键须等前面的拼音处理完，避免异步结果覆盖编辑操作。
            hardwareKeys[identity] = true
            val copy = KeyEvent(event)
            submit("raw", after = { currentInputConnection?.sendKeyEvent(copy) })
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val deferredUp=KeyEvent(event)
        if(voice.deferUntilFinal { onKeyUp(keyCode,deferredUp) })return true
        val forwarded = hardwareKeys.remove(event.deviceId to keyCode)
            ?: return super.onKeyUp(keyCode, event)
        if (forwarded && (ready || loading) && active) {
            val copy = KeyEvent(event)
            submit("query", after = { currentInputConnection?.sendKeyEvent(copy) })
        }
        return true
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        voice.cancel(false)
        voice.editorStarted(attribute)
        active = true
        selectionStart = attribute.initialSelStart; selectionEnd = attribute.initialSelEnd; expectedSelection = null
        val kind = attribute.inputType and InputType.TYPE_MASK_CLASS
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        // 自家短语输入码编辑器请求字母原样输入；其他应用不受此私有约定影响。
        val literalCode = attribute.packageName == packageName && attribute.privateImeOptions == "dev.qingstudy.ime.literal"
        direct = literalCode || kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE ||
            (kind == InputType.TYPE_CLASS_TEXT && variation in listOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        numberLayout = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE
        keyboard?.reloadAppearance()
        keyboard?.editor(attribute)
        keyboard?.setDirectLayout(direct)
        keyboard?.setNumberLayout(numberLayout)
        reset()
        val privateInput = direct || attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        submit("configure", Preferences.engine(this, privateInput))
    }
    override fun onFinishInput() {
        voice.cancel(false)
        active = false
        keyboard?.closeOverlays()
        reset()
        super.onFinishInput()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        // 系统返回/手势收起仍在同一编辑器；先排完尾音期间按下的键，再结束组合。
        // 切换编辑器时不排空，避免把上一输入框的文字写到新焦点。
        if(!finishingInput)voice.settlePendingInput()
        voice.cancel(false)
        currentInputConnection?.finishComposingText()
        if(!finishingInput&&pending>0)submit("raw",after={ reset() }) else reset()
        super.onFinishInputView(finishingInput)
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val moved = oldSelStart != newSelStart || oldSelEnd != newSelEnd
        val expected = expectedSelection
        val ownCommit = newSelStart == expected && newSelEnd == expected
        val compositionMoved = composing.isNotEmpty() && pending == 0 &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        val continuationMoved = lastFrame.continuation && pending == 0 && !voice.busy && moved && !ownCommit
        // finishComposingText 的通知可能晚于后续拼音帧，清空前核实宿主的当前选区。
        if ((compositionMoved || continuationMoved) &&
            !EditorSelection.isCurrent(currentInputConnection, newSelStart, newSelEnd)) return
        voice.selectionChanged(newSelStart, newSelEnd, candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if (ownCommit) expectedSelection = null
        else if (continuationMoved) {
            expectedSelection = null
            reset()
        }
        if (compositionMoved) {
            currentInputConnection?.finishComposingText()
            reset()
        }
    }
    private fun reset() {
        keyboard?.cancelGestures()
        epoch++
        pending = 0
        composing = ""
        lastFrame = Frame("", emptyList(), "", false)
        keyboard?.setPending(false)
        if (ready) keyboard?.render(lastFrame)
        engine.step("reset") { }
    }
    private fun key(key: String, argument: String) {
        if(key in listOf("hide","switch","voice-settings"))voice.settlePendingInput()
        if(key !in listOf("displayed","hide","switch","voice","voicehold")&&!key.startsWith("voice-")&&voice.deferUntilFinal { key(key,argument) })return
        if (key == "voice-settings") { startActivity(Intent(this, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        if (key.startsWith("voice-")) { voice.command(key.removePrefix("voice-"), argument); return }
        if (key == "voice" || key == "voicehold") {
            if (!active || direct || (!ready && !loading)) return
            val start = { if (key != "voicehold" || keyboard?.voiceHoldActive == true) voice.start(key == "voicehold") }
            if (pending > 0 || !ready || composing.isNotEmpty()) submit("raw", after = start)
            else { submit("context-break", after = start) }
            return
        }
        if (key != "displayed" && voice.busy) voice.cancel(false)
        if (key == "hide") {
            // 排在已按下的字符之后收起，保留尚未选词的原文；空闲/加载失败也能收起。
            if (active && (ready || loading) && (pending > 0 || composing.isNotEmpty())) {
                submit("raw", after = { requestHideSelf(0) })
            } else requestHideSelf(0)
            return
        }
        if (key == "switch") {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            return
        }
        if (key == "appearance") {
            startActivity(Intent(this, AppearanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        if (key == "input-settings") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "input").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        if (key == "learning-settings") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "learning").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        if (key == "settings") {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if ((!ready && !loading) || !active) return
        if (key == "displayed") { engine.step(key, argument) { }; return }
        if (key == "previous" || key == "next") { keyboard?.turnPage(if (key == "next") 1 else -1); return }
        if (key == "details") { showDetails(argument.toInt()); return }
        if (key == "layout") {
            val update = {
                numberLayout = argument == "number"
                keyboard?.setNumberLayout(numberLayout)
                Unit
            }
            if (lastFrame.panel.isNotEmpty()) submit("panel-close", after = update)
            else if (direct || lastFrame.expression) update() else submit("raw", after = update)
            return
        }
        if (key in listOf("panel-open", "panel-select", "panel-category", "panel-close", "panel-space", "panel-delete")) {
            submit(key, argument); return
        }
        if (direct) {
            when (key) {
                "delete" -> deleteText()
                "space" -> currentInputConnection?.commitText(" ", 1)
                "enter" -> enter()
                "select", "english", "panel", "cancel", "panel-close", "panel-group", "panel-prev", "panel-next", "block", "unblock" -> Unit
                "letter" -> currentInputConnection?.commitText(argument, 1)
                "literal" -> currentInputConnection?.commitText(argument, 1)
                "punctuate" -> currentInputConnection?.commitText(argument, 1)
                else -> currentInputConnection?.commitText(key, 1)
            }
            return
        }
        when (key) {
            "phonetic" -> submit("graphic", argument)
            "select", "cancel", "english", "panel", "translate", "forget", "punctuate", "block", "unblock" -> submit(key, argument)
            "delete" -> submit(if (lastFrame.panel.isEmpty()) "delete" else "panel-delete")
            "space" -> if (lastFrame.panel.isNotEmpty()) submit("panel-space") else submit("space", if (pending == 0) keyboard?.candidateIndex(1)?.toString() ?: "" else "")
            "panel-group" -> submit(key, "1")
            "panel-prev", "panel-next" -> submit("panel-section", if (key == "panel-next") "1" else "-1")
            "letter" -> {
                if (argument.all { it.isLowerCase() } || lastFrame.english ||
                    (argument in listOf("V", "U") && lastFrame.scheme != "全拼")) submit("input", argument)
                else submit("raw", after = { currentInputConnection?.commitText(argument, 1) })
            }
            "enter" -> submit("raw", after = ::enter)
            "literal" -> if (lastFrame.expression) submit("input", argument) else submit("raw", after = { currentInputConnection?.commitText(argument, 1) })
            "，", "。" -> submit("punctuate", if (key == "，") "," else ".")
            else -> submit("input", key)
        }
    }
    private fun showDetails(index: Int) { keyboard?.showDetails(index) }
    private fun submit(action: String, argument: String = "", after: () -> Unit = {}) {
        val token = epoch
        val blockingWord = if (action == "block") lastFrame.candidates.getOrNull(argument.toIntOrNull() ?: -1)?.first else null
        pending++
        keyboard?.setPending(true)
        engine.step(action, argument) { result ->
            if (token != epoch || !active) return@step
            pending--
            result.onSuccess { frame ->
                frame.blockedWords?.let { words ->
                    Preferences.save(this, Preferences.read(this).put("blockedWords", org.json.JSONArray(words)))
                }
                val connection = currentInputConnection ?: return@onSuccess
                if (frame.commit.isNotEmpty() && selectionStart >= 0) {
                    expectedSelection = (minOf(selectionStart, selectionEnd) - lastFrame.preedit.length).coerceAtLeast(0) + frame.commit.length + frame.preedit.length
                }
                connection.beginBatchEdit()
                try {
                    if (frame.delete) deleteText()
                    if (frame.commit.isNotEmpty()) connection.commitText(frame.commit, 1)
                    if (frame.input.isNotEmpty()) connection.setComposingText(frame.preedit, 1)
                    else if (frame.commit.isEmpty() && composing.isNotEmpty()) connection.setComposingText("", 1)
                    if (frame.input.isEmpty()) connection.finishComposingText()
                    composing = frame.input
                    after()
                } finally { connection.endBatchEdit() }
                lastFrame = frame
                keyboard?.render(frame)
                if (action == "unblock") keyboard?.closeOverlays()
                if (blockingWord != null) keyboard?.showBlockUndo(blockingWord)
            }.onFailure {
                if (action in listOf("block", "unblock")) {
                    android.widget.Toast.makeText(this, it.message ?: "候选管理失败", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    keyboard?.status("引擎异常：${it.message}；请切换输入法", false)
                    ready = false
                }
            }
            keyboard?.setPending(pending != 0)
        }
    }
    private fun deleteText() {
        currentInputConnection?.let { connection ->
            if (!connection.getSelectedText(0).isNullOrEmpty()) connection.commitText("", 1)
            else {
                val before = connection.getTextBeforeCursor(256, 0)?.toString().orEmpty()
                if (before.isEmpty()) connection.deleteSurroundingTextInCodePoints(1, 0)
                else connection.deleteSurroundingText(EditorDeletion.lastGraphemeLength(before), 0)
            }
        }
    }
    private fun enter() {
        val info = currentInputEditorInfo ?: return
        val action = EditorAction.action(info)
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
    override fun onDestroy() {
        voice.close()
        keyboard?.cancelGestures()
        epoch++
        engine.close()
        super.onDestroy()
    }
}
