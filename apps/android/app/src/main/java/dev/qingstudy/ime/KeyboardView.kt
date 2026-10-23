// R4 键盘：可调比例与安全区、常驻入口、副符号和连续退格。
package dev.qingstudy.ime

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.graphics.Rect
import org.json.JSONArray

class KeyboardView(context: Context, private val action: (String, String) -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val compact = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var appearance = KeyboardAppearance.read(context)
    private var palette = KeyboardPalette(context, appearance)
    private val feedback = KeyboardFeedback(context)
    private var usableWidth = resources.configuration.screenWidthDp
    private val rowHeight: Int get() = appearance.resolvedRowHeight(usableWidth, compact)
    private val keyAreaHeight: Int get() = if (phoneticMode) (rowHeight * 4 / 5).coerceAtLeast(if (compact) 27 else 36) * 5
        else rowHeight * 4 + if (appearance.numberRow && !compact) 30 else 0
    private var safeInsets = Rect()
    private var voicePanel: VoicePanelView? = null
    private var holdingVoice = false
    val voiceHoldActive: Boolean get() = holdingVoice
    private var voiceHover = "finish"
    private var enterLabel = "换行"

    fun editor(info: android.view.inputmethod.EditorInfo?) {
        val label = EditorAction.label(info)
        if (label != enterLabel) { enterLabel = label; buildKeys() }
    }

    fun showVoice(held: Boolean, label: String) {
        feedback.recording = true
        if (voicePanel == null) {
            voicePanel = VoicePanelView(context) { key, argument -> action("voice-$key", argument) }
            voicePanel?.zoneFeedback = { view -> feedback.press(view, sound = false) }
            voicePanel?.let { feedback.bind(it) }
            voicePanel?.onHoldZoneChanged { centre -> voiceBackground(centre) }
            addView(voicePanel, LayoutParams(-1, dp(32 + appearance.candidateHeight(context) + keyAreaHeight)))
        }
        holdingVoice = held
        if(held)voiceBackground(true)
        voicePanel?.mode(held, label)
        keys.visibility = GONE; toolbar.visibility = GONE; candidateStrip.visibility = GONE; idleBar?.visibility = GONE
    }
    fun updateVoice(text: String, message: String, running: Boolean, processing: Boolean) { voicePanel?.update(text, message, running, processing) }
    fun voiceRecovery() { voicePanel?.recovery() }
    fun voiceLevel(level: Float) { voicePanel?.level(level) }
    fun voiceCandidates(items: List<Pair<String, String>>) { voicePanel?.candidates(items) }
    fun hideVoice(preservePendingHold: Boolean = false) {
        feedback.recording = false
        holdingVoice = preservePendingHold
        setBackgroundColor(palette.background)
        voicePanel?.let { removeView(it) }; voicePanel = null
        keys.visibility = VISIBLE; updateTop()
    }
    private fun voiceBackground(centre: Boolean) {
        setBackgroundColor(if (centre) palette.background else palette.function)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (holdingVoice) {
            val panel = voicePanel
            if (panel == null) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) holdingVoice = false
                return true
            }
            voiceHover = panel.hover(event.x - panel.left, event.y - panel.top)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> { holdingVoice = false; action("voice-$voiceHover", "") }
                MotionEvent.ACTION_CANCEL -> { holdingVoice = false; action("voice-interrupt", "") }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private var idleBar: IdleKeyboardBar? = null
    private var expanded = false
    private var toolsOpen = false
    private var detailIndex: Int? = null
    private var blockedUndo: String? = null
    private var englishButton: Button? = null
    private val expand = Button(context)
    private val candidateStrip = LinearLayout(context)
    private val status = TextView(context)
    private val candidates = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val keys = LinearLayout(context)
    private var pending = false
    private var current: Frame? = null
    private var numbers = false
    private var direct = false
    private val phoneticMode: Boolean get() = current?.scheme == "大千注音" && current?.english == false && !direct
    private val zhuyinLayout: Boolean get() = phoneticMode && !numbers && !panelMode
    private var available = false
    private var shifted = false
    private var page = 0
    private var panelMode = false
    private val toolbar = LinearLayout(context).apply { isBaselineAligned = false }
    private var visibleIndexes = emptyList<Int>()
    private val reportVisible = Runnable {
        val rect = Rect()
        val indexes = candidateViews(if (expanded) keys else candidates).filter { it.isShown && it.getLocalVisibleRect(rect) }
            .mapNotNull { it.tag as? Int }.distinct()
        if (indexes != visibleIndexes && !pending && !panelMode) {
            visibleIndexes = indexes
            action("displayed", JSONArray(indexes).toString())
        }
    }
    fun candidateIndex(digit: Int): Int? = current?.let { frame ->
        (page * 9 + digit - 1).takeIf { it in frame.candidates.indices }
    }
    fun turnPage(delta: Int) {
        val frame = current ?: return
        page = (page + delta).coerceIn(0, ((frame.candidates.size - 1) / 9).coerceAtLeast(0))
        showCandidates(frame)
    }

    init {
        orientation = VERTICAL
        applySafePadding()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnApplyWindowInsetsListener { view, insets ->
            safeInsets = KeyboardInsets.resolve(insets, view.rootWindowInsets)
            applySafePadding()
            insets
        }
        setBackgroundColor(palette.background)
        status.setTextColor(palette.muted)
        status.textSize = 14f
        status.isSingleLine = true
        status.ellipsize = android.text.TextUtils.TruncateAt.START
        status.gravity = Gravity.CENTER_VERTICAL
        status.setPadding(dp(10), 0, dp(10), 0)
        toolbar.addView(toolButton("☰", "menu") { toolsOpen = !toolsOpen; detailIndex = null; expanded = false; buildKeys(); updateExpand() }, LayoutParams(dp(36), -1))
        toolbar.addView(status, LayoutParams(0, -1, 1f))
        toolbar.addView(toolButton("", "details") { if (!pending) action("details", (page * 9).toString()) }.apply {
            setCompoundDrawablesWithIntrinsicBounds(null, BookIcon(palette.muted, dp(20)), null, null)
        }, LayoutParams(dp(36), -1))
        toolbar.addView(toolButton("⚙", "settings") { action("settings", "") }, LayoutParams(dp(36), -1))
        toolbar.addView(toolButton("", "voice") { action("voice", "") }.apply {
            tag = "voice-entry"
            setCompoundDrawablesWithIntrinsicBounds(null, context.getDrawable(android.R.drawable.ic_btn_speak_now)?.apply { setTint(palette.muted) }, null, null)
        }, LayoutParams(dp(36), -1))
        toolbar.addView(toolButton("⌄", "hide") { action("hide", "") }.apply {
            contentDescription = "收起键盘"; textSize = 24f
        }, LayoutParams(dp(36), -1))
        addView(toolbar, LayoutParams(-1, dp(32)))
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(candidates)
        scroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            removeCallbacks(reportVisible); postDelayed(reportVisible, 80)
        }
        candidateStrip.addView(scroll, LayoutParams(0, -1, 1f))
        expand.apply {
            text = "⌄"; textSize = 22f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0); setTextColor(palette.ink); background = palette.surface(palette.background)
            contentDescription = "展开候选"
            setOnClickListener { expanded = !expanded; toolsOpen = false; detailIndex = null; buildKeys(); updateExpand(); post(reportVisible) }
        }
        candidateStrip.addView(expand, LayoutParams(dp(32), -1))
        addView(candidateStrip, LayoutParams(-1, dp(appearance.candidateHeight(context))))
        buildIdleBar()
        keys.orientation = VERTICAL
        addView(keys)
        buildKeys()
        status("正在加载本地词库…", false)
        feedback.bind(toolbar); feedback.bind(expand)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = ((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight + dp(10)) / density).toInt()
        if (widthDp > 0 && widthDp != usableWidth) {
            val oldHeight = rowHeight
            usableWidth = widthDp
            // 横屏导航栏 inset 会分帧到达；行高不变时只改缩进，保留正在点击的按键。
            if (rowHeight != oldHeight) buildKeys()
            else for (index in 0 until keys.childCount) {
                keys.getChildAt(index).takeIf { it.tag == "middle-row" }?.setPadding(dp(usableWidth / 20), 0, dp(usableWidth / 20), 0)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun dispatch(key: String, value: String = "") {
        when (key) {
            "menu" -> { toolsOpen = !toolsOpen; detailIndex = null; expanded = false; buildKeys(); updateExpand() }
            "numbers" -> action("layout", "number")
            "panel-close" -> action("layout", "letters")
            "symbols", "emoji" -> action("panel-open", if (key == "symbols") "符号" else "表情")
            else -> action(key, value)
        }
    }
    private fun buildIdleBar() {
        idleBar?.let { removeView(it) }
        idleBar = IdleKeyboardBar(context, palette, 32 + appearance.candidateHeight(context), ::dispatch)
        feedback.bind(idleBar!!)
        addView(idleBar, 2, LayoutParams(-1, dp(32 + appearance.candidateHeight(context))))
        updateTop()
    }
    private fun updateTop() {
        val voiceSettings = VoiceSettings.read(context)
        idleBar?.voiceProvider(voiceSettings.optString("provider", "system"))
        toolbar.findViewWithTag<View>("voice-entry")?.apply {
            isEnabled = available
            visibility = if (!direct && voiceSettings.optBoolean("entry", true)) VISIBLE else GONE
        }
        if (voicePanel != null) { idleBar?.visibility = GONE; toolbar.visibility = GONE; candidateStrip.visibility = GONE; return }
        val idle = available && current?.input.isNullOrEmpty() && current?.continuation != true
        idleBar?.visibility = if (idle) VISIBLE else GONE
        idleBar?.contentDescription = status.contentDescription
        toolbar.visibility = if (idle) GONE else VISIBLE
        candidateStrip.visibility = if (idle) GONE else VISIBLE
    }
    fun setNumberLayout(value: Boolean) {
        if (numbers == value) return
        numbers = value
        buildKeys()
        current?.let { render(it) } ?: status(status.text.toString(), available)
    }
    fun setDirectLayout(value: Boolean) {
        if (direct == value) return
        direct = value
        buildKeys()
    }
    private fun buildKeys() {
        try {
        cancelGestures()
        englishButton = null
        keys.removeAllViews()
        // 每行 dp 分别取整会差 1–4px；固定总预算，切换面板时不跳动。
        keys.layoutParams = keys.layoutParams.apply { height = dp(keyAreaHeight) }
        blockedUndo?.let { word ->
            val content = overlay("候选管理")
            content.addView(TextView(context).apply {
                text = "不再推荐「$word」\n可在个人学习中随时恢复。"
                textSize = 16f; setTextColor(palette.ink); setPadding(0, dp(8), 0, dp(8))
            })
            content.addView(toolButton("撤销屏蔽", "unblock") {
                if (!pending) action("unblock", word)
            }, LayoutParams(-1, dp(48)))
            return
        }
        if (panelMode) { buildPanel(); return }
        if (detailIndex != null) { buildDetails(); return }
        if (toolsOpen) { buildTools(); return }
        if (expanded) { buildExpanded(); return }
        if (appearance.numberRow && !compact && !zhuyinLayout && !numbers) row("1234567890".map { it.toString() to "auxiliary" }, heightOverride = 30)
        if (numbers) {
            keys.addView(NumericKeyboard(context, dp(keyAreaHeight)) { label, key, height ->
                keyButton(label, key, (height / density).toInt())
            }, LayoutParams(-1, dp(keyAreaHeight)))
        } else if (zhuyinLayout) {
            for (letters in listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")) {
                row(letters.map { (current?.phoneticKeys?.get(it.toString()) ?: it.toString()) to "phonetic:$it" })
            }
            row(listOf((if (current?.english == true) "英" else "中") to "english", "123" to "layout", "，" to "，", "空格" to "space", "。" to "。", "⌫" to "delete", "回车" to "enter"))
        } else {
            row("qwertyuiop".map { it.toString() to "letter" })
            row("asdfghjkl".map { it.toString() to "letter" }, true)
            row(listOf((if (shifted) "⇪" else "⇧") to "shift") + "zxcvbnm".map { it.toString() to "letter" } + ("⌫" to "delete"))
            val left = if (appearance.splitBottom) listOf("符号" to "symbols", "123" to "layout") else listOf("?123" to "symbols", "☺" to "emoji")
            row(left + listOf("，" to "，", "空格" to "space", (if (current?.english == true) "英" else "中") to "english", "回车" to "enter"))
        }
        } finally { feedback.bind(keys) }
    }
    private fun dp(value: Int) = (value * density).toInt()
    private fun row(labels: List<Pair<String, String>>, stagger: Boolean = false, heightOverride: Int? = null) {
        // 不同字号的功能键按整格对齐，避免基线对齐把窄键挤出末行。
        val row = LinearLayout(context).apply { isBaselineAligned = false }
        val height = heightOverride ?: if (zhuyinLayout) (rowHeight * 4 / 5).coerceAtLeast(if (compact) 27 else 36) else rowHeight
        if (stagger) { row.tag = "middle-row"; row.setPadding(dp(usableWidth / 20), 0, dp(usableWidth / 20), 0) }
        for ((label, key) in labels) {
            val weight = when (key) { "space" -> 3.4f; "enter" -> 1.7f; "symbols" -> 1.12f; "emoji" -> .86f; "，" -> .8f; "english" -> 1.1f; "shift", "delete" -> 1.42f; else -> 1f }
            row.addView(keyButton(label, key, height), LayoutParams(0, dp(height), weight))
        }
        keys.addView(row, LayoutParams(-1, dp(height)))
    }
    private fun keyButton(label: String, key: String, height: Int): KeyboardKey =
            KeyboardKey(context).apply {
                feedbackKind = when (key) { "delete" -> "delete"; "space", "enter" -> "action"; else -> "letter" }
                text = when (key) { "enter" -> enterLabel; "letter" -> label.uppercase(); "emoji" -> "☺︎"; else -> label }
                typeface = android.graphics.Typeface.DEFAULT
                stateListAnimator = null; elevation = 0f
                val font = if (key == "auxiliary") 16 else if (key in listOf("enter", "english", "symbols", "space", "layout")) 15 else if (key == "，") 22 else if (label.length > 1 && !key.startsWith("phonetic:")) 14 else appearance.keySize
                setAutoSizeTextTypeUniformWithConfiguration(10, font, 1, TypedValue.COMPLEX_UNIT_SP)
                includeFontPadding = false
                isSingleLine = true
                setHorizontallyScrolling(false)
                gravity = Gravity.CENTER
                minHeight = 0; minimumHeight = 0
                setTextColor(if (key == "enter") palette.onAccent else palette.ink)
                background = palette.surface(when {
                    key == "enter" -> palette.accent
                    key in listOf("shift", "delete", "switch", "layout", "symbols", "emoji") -> palette.function
                    else -> palette.key
                }, true)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(appearance.keyGap / 2 + 1), 0, dp(appearance.keyGap / 2 + 1), 0)
                contentDescription = if (key == "english") "工具 english" else "按键 $label"
                if (key == "english") englishButton = this
                setOnClickListener {
                    if (key == "shift") { shifted = !shifted; buildKeys(); return@setOnClickListener }
                    if (key.startsWith("phonetic:")) { action("phonetic", key.substringAfter(':')); return@setOnClickListener }
                    dispatch(if (key == "auxiliary") "punctuate" else key, when (key) {
                        "letter" -> if (shifted) label.uppercase() else label
                        "literal", "auxiliary" -> label
                        "layout" -> if (numbers) "letters" else "number"
                        else -> ""
                    })
                }
                if (key == "delete") { repeatAction = { action("delete", "") }; canRepeat = { !pending && available } }
                if (key == "space" && !direct) {
                    val showVoiceHint = !numbers && !compact && height >= 44
                    // 交给同一文本布局排两行，随系统字体和自动字号缩放，避免叠画副标。
                    if (showVoiceHint) {
                        isSingleLine = false; maxLines = 2
                        text = android.text.SpannableString("长按说话\n$label").apply {
                            setSpan(android.text.style.RelativeSizeSpan(2f / 3f), 0, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(android.text.style.ForegroundColorSpan(palette.muted), 0, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    setOnLongClickListener { if (available) { holdingVoice = true; action("voicehold", ""); true } else false }
                }
                if (key == "letter" && appearance.auxiliary) {
                    secondary = KeySymbols.primary(label).orEmpty(); secondaryColor = palette.muted
                    if (!compact) setPadding(paddingLeft, dp(12), paddingRight, dp(2))
                    else { secondary = "" } // 横屏行高较矮，保留手势但不挤压主字母。
                    if (appearance.swipeSymbols) onSwipe = { dispatch("punctuate", KeySymbols.primary(label).orEmpty()) }
                }
                if (key == "letter" && appearance.longPressSymbols) setOnLongClickListener {
                    showChoices(KeySymbols.choices(label, appearance.auxiliary)) { dispatch("punctuate", it) }; true
                }
                if (key == "，") setOnLongClickListener {
                    android.widget.PopupMenu(context, this).apply {
                        for ((title, value) in listOf("。" to ".", "？" to "?", "！" to "!", "、" to "\\", "：" to ":", "；" to ";")) {
                            menu.add(title).setOnMenuItemClickListener { dispatch("punctuate", value); true }
                        }
                        show()
                    }; true
                }
            }
    fun status(message: String, enabled: Boolean) {
        available = enabled
        status.text = if (message.endsWith("本地运行")) "青学" else message
        status.contentDescription = message
        updateTop()
        descendants(keys).filterIsInstance<Button>().forEach {
            it.isEnabled = enabled || it.contentDescription == "按键 切换"
        }
    }
    fun setPending(value: Boolean) {
        pending = value
        candidateViews(this).forEach { it.isEnabled = !value }
        expand.isEnabled = !value && !panelMode && current?.candidates?.isNotEmpty() == true
        (toolbar.getChildAt(2) as Button).isEnabled = expand.isEnabled
        if (detailIndex != null) descendants(keys).filterIsInstance<Button>().forEach { it.isEnabled = !value }
        if (panelMode) descendants(keys).filterIsInstance<Button>().forEach { it.isEnabled = !value && available }
        if (!value) { removeCallbacks(reportVisible); postDelayed(reportVisible, 80) }
    }
    fun render(frame: Frame) {
        val changed = current?.input != frame.input || current?.candidates != frame.candidates || current?.panel != frame.panel
        // 普通拼音中英切换共用字母键，不销毁正在接受快速点击的按键。
        val schemeChanged = current?.scheme != frame.scheme ||
            (frame.scheme == "大千注音" && current?.english != frame.english)
        current = frame
        englishButton?.text = if (frame.english) "英" else "中"
        if (schemeChanged) buildKeys()
        if (changed) { page = 0; if (expanded || detailIndex != null || toolsOpen || blockedUndo != null) { expanded = false; toolsOpen = false; detailIndex = null; blockedUndo = null; buildKeys() } }
        status(when {
            frame.panel.isNotEmpty() -> frame.panel
            frame.input.isNotEmpty() -> frame.marked
            frame.continuation -> "续词 · 本机学习"
            numbers -> "数字 · 本地输入"
            frame.english -> "英文 · 补全与纠错"
            else -> "${frame.scheme} · 英文释义 · 本地运行"
        }, true)
        val panelChanged = panelMode != frame.panel.isNotEmpty()
        panelMode = frame.panel.isNotEmpty()
        // 同一分类连续选词、空格或退格不重建面板，保留用户滚动到的位置。
        if (panelChanged || (panelMode && changed)) buildKeys()
        showCandidates(frame)
    }
    private fun showCandidates(frame: Frame) {
        candidates.removeAllViews()
        visibleIndexes = emptyList()
        if (!panelMode) frame.candidates.drop(page * 9).take(9).forEachIndexed { offset, candidate ->
            val index = page * 9 + offset
            candidates.addView(CandidateView(context, frame.displays.getOrElse(index) { candidate.first }, candidate.second, compact,
                frame.fresh.getOrElse(index) { false }, appearance, index == page * 9).apply {
                tag = index
                isEnabled = !pending
                setOnClickListener { if (!pending) action("select", index.toString()) }
                setOnLongClickListener { if (!pending) action("details", index.toString()); true }
            })
        }
        scroll.scrollTo(0, 0)
        feedback.bind(candidates)
        updateExpand()
        if (expanded) buildExpanded()
        removeCallbacks(reportVisible); postDelayed(reportVisible, 80)
    }
    fun reloadAppearance() {
        feedback.settings = FeedbackSettings.read(context)
        buildIdleBar()
        val next = KeyboardAppearance.read(context)
        if (next == appearance && KeyboardPalette(context, next).themeId == palette.themeId) return
        appearance = next
        applySafePadding()
        palette = KeyboardPalette(context, appearance)
        setBackgroundColor(palette.background)
        status.setTextColor(palette.muted)
        for (i in 0 until toolbar.childCount) (toolbar.getChildAt(i) as? Button)?.apply {
            setTextColor(palette.ink); background = palette.surface(palette.background)
        }
        (toolbar.getChildAt(2) as Button).setCompoundDrawablesWithIntrinsicBounds(null, BookIcon(palette.muted, dp(20)), null, null)
        (toolbar.getChildAt(4) as Button).compoundDrawables.filterNotNull().forEach { it.setTint(palette.muted) }
        expand.setTextColor(palette.ink); expand.background = palette.surface(palette.background)
        candidateStrip.layoutParams.height = dp(appearance.candidateHeight(context))
        buildIdleBar()
        buildKeys()
        current?.let { showCandidates(it) }
        requestLayout()
    }
    private fun updateExpand() {
        (toolbar.getChildAt(2) as Button).isEnabled = !pending && !panelMode && current?.candidates?.isNotEmpty() == true
        expand.text = if (expanded) "⌃" else "⌄"
        expand.contentDescription = if (expanded) "收起候选" else "展开候选"
        expand.isEnabled = !pending && !panelMode && current?.candidates?.isNotEmpty() == true
    }
    private fun candidateViews(view: View): List<CandidateView> = when (view) {
        is CandidateView -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { candidateViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun buildExpanded() {
        keys.removeAllViews()
        val frame = current ?: return
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        frame.candidates.chunked(3).forEachIndexed { line, items ->
            val row = LinearLayout(context)
            items.forEachIndexed { column, candidate ->
                val index = line * 3 + column
                row.addView(CandidateView(context, frame.displays.getOrElse(index) { candidate.first }, candidate.second,
                    compact, frame.fresh.getOrElse(index) { false }, appearance, index == page * 9).apply {
                    tag = index; isEnabled = !pending
                    setOnClickListener { if (!pending) action("select", index.toString()) }
                    setOnLongClickListener { if (!pending) action("details", index.toString()); true }
                }, LayoutParams(0, dp(appearance.candidateHeight(context)), 1f))
            }
            grid.addView(row)
        }
        keys.addView(ScrollView(context).apply {
            addView(grid)
            setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
                removeCallbacks(reportVisible); postDelayed(reportVisible, 80)
            }
        }, LayoutParams(-1, dp(keyAreaHeight)))
        feedback.bind(keys)
    }
    private fun toolButton(label: String, key: String, click: () -> Unit) = Button(context).apply {
        text = label; textSize = 16f; contentDescription = "工具 $key"
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0); isAllCaps = false; includeFontPadding = false
        stateListAnimator = null; elevation = 0f
        setTextColor(palette.muted); background = palette.surface(palette.background)
        setOnClickListener { click() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    fun cancelGestures() { descendants(keys).filterIsInstance<KeyboardKey>().forEach { it.cancelInteraction() } }
    override fun onDetachedFromWindow() { feedback.close(); cancelGestures(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); feedback.prepare() }
    private fun applySafePadding() {
        setPadding(dp(5) + safeInsets.left, dp(3), dp(5) + safeInsets.right, dp(4 + appearance.bottomSpace) + safeInsets.bottom)
    }
    fun closeOverlays() { toolsOpen = false; detailIndex = null; blockedUndo = null; expanded = false; buildKeys(); updateExpand() }
    fun showBlockUndo(word: String) {
        detailIndex = null; toolsOpen = false; expanded = false; blockedUndo = word
        buildKeys(); updateExpand()
    }
    fun showDetails(index: Int) {
        if (pending || current?.candidates?.getOrNull(index) == null) return
        detailIndex = index; expanded = false; toolsOpen = false; buildKeys(); updateExpand()
    }
    private fun overlay(title: String): LinearLayout {
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply { text = title; textSize = 13f; setTextColor(palette.muted) }, LayoutParams(0, -2, 1f))
        header.addView(toolButton("返回键盘", "close") { closeOverlays() }, LayoutParams(dp(90), dp(36)))
        content.addView(header)
        keys.addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, dp(keyAreaHeight)))
        return content
    }
    private fun buildTools() {
        val content = overlay("常用工具")
        val separator = if (current?.scheme in listOf("微软双拼", "搜狗双拼")) ";" else "'"
        val items = listOf("语音输入" to "voice", "语音设置" to "voice-settings", "数字键盘" to "numbers", "表情与符号" to "panel", "切换输入法" to "switch", "上一页候选" to "previous", "下一页候选" to "next",
            "大小与字体" to "appearance", "输入方案" to "input-settings", "个人学习" to "learning-settings", "拼音分隔 $separator" to separator, "全部设置" to "settings")
        for (itemsInRow in items.chunked(2)) {
            val line = LinearLayout(context)
            for ((label, key) in itemsInRow) line.addView(toolButton(label, key) {
                toolsOpen = false
                if (key == "previous" || key == "next") { turnPage(if (key == "next") 1 else -1); buildKeys() }
                else { buildKeys(); dispatch(key, "") }
            }, LayoutParams(0, dp(44), 1f))
            content.addView(line)
        }
    }
    private fun buildDetails() {
        val frame = current ?: return
        val index = detailIndex ?: return
        val candidate = frame.candidates.getOrNull(index) ?: return
        val content = overlay("完整释义")
        content.addView(TextView(context).apply { text = candidate.first; textSize = 22f; setTextColor(palette.ink); setPadding(0, dp(6), 0, dp(6)) })
        val senses = frame.senses.getOrElse(index) { emptyList() }
        content.addView(TextView(context).apply { text = senses.joinToString("; ").ifEmpty { candidate.second.ifEmpty { "暂无英文释义" } }; textSize = 15f; setTextColor(palette.muted) })
        fun command(label: String, key: String, value: String) {
            content.addView(toolButton(label, key) {
                if (!pending && current === frame) action(key, value)
            }.apply { contentDescription = label; isEnabled = !pending; minHeight = dp(44) }, LayoutParams(-1, -2))
        }
        command(if (frame.custom.getOrElse(index) { false }) "输入短语" else "输入中文", "select", index.toString())
        val management = LinearLayout(context)
        management.addView(toolButton("清除学习排序", "forget") {
            if (!pending && current === frame) action("forget", index.toString())
        }, LayoutParams(0, dp(44), 1f))
        management.addView(toolButton("不再推荐", "block") {
            if (!pending && current === frame && frame.blockAllowed && frame.blockable.getOrElse(index) { false }) action("block", index.toString())
        }.apply {
            alpha = if (frame.blockAllowed && frame.blockable.getOrElse(index) { false }) 1f else 0.4f
        }, LayoutParams(0, dp(44), 1f))
        content.addView(management)
        command("管理个人学习", "learning-settings", "")
        senses.forEachIndexed { sense, text -> command("输入译词：$text", "translate", "$index:$sense") }
    }
    private fun buildPanel() {
        val frame = current ?: return
        keys.addView(SymbolPanelView(context, frame, rowHeight, palette, pending, ::dispatch, keyAreaHeight), LayoutParams(-1, dp(keyAreaHeight)))
    }
}
