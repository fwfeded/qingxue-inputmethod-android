//! 外观设置：固定真实候选/按键预览，主题、反馈和尺寸独立滚动并即时保存。
package dev.qingstudy.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class AppearanceActivity : Activity() {
    private var appearance = KeyboardAppearance()
    private lateinit var feedback: KeyboardFeedback
    private lateinit var preview: AppearancePreview
    private lateinit var ui: SettingsUi
    private var tab = "themes"
    private val positions = mutableMapOf<String, Int>()
    private var previewLayoutListener: View.OnLayoutChangeListener? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = KeyboardAppearance.read(this)
        feedback = KeyboardFeedback(this)
        tab = (savedInstanceState?.getString("tab") ?: intent.getStringExtra("appearance_tab"))
            .takeIf { it in listOf("themes", "feedback", "size") } ?: "themes"
        for (id in listOf("themes", "feedback", "size")) positions[id] = savedInstanceState?.getInt("scroll-$id") ?: 0
        showPage(positions[tab] ?: 0)
    }
    override fun onSaveInstanceState(out: Bundle) {
        positions[tab] = ui.scroll.scrollY
        out.putString("tab", tab)
        positions.forEach { (id, value) -> out.putInt("scroll-$id", value) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() {
        super.onResume()
        feedback.settings = FeedbackSettings.read(this)
        val next = KeyboardAppearance.read(this)
        if (next != appearance) { appearance = next; showPage(ui.scroll.scrollY) }
    }
    override fun onPause() { feedback.close(); super.onPause() }
    override fun onDestroy() { feedback.close(); super.onDestroy() }
    private fun saveAppearance(rebuild: Boolean = false) {
        KeyboardAppearance.save(this, appearance)
        if (rebuild) showPage(ui.scroll.scrollY) else {
            val scroll = ui.scroll
            val oldY = scroll.scrollY
            previewLayoutListener?.let { scroll.removeOnLayoutChangeListener(it) }
            previewLayoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    scroll.removeOnLayoutChangeListener(this)
                    previewLayoutListener = null
                    scroll.scrollTo(0, oldY + t - ot)
                }
            }
            scroll.addOnLayoutChangeListener(previewLayoutListener)
            preview.update(appearance)
        }
    }
    private fun saveFeedback(rebuild: Boolean = false) {
        FeedbackSettings.save(this, feedback.settings)
        if (rebuild) showPage(ui.scroll.scrollY)
    }
    private fun showPage(y: Int = 0) {
        ui = SettingsUi(this)
        val fixed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        preview = AppearancePreview(this, feedback)
        preview.update(appearance)
        fixed.addView(preview)
        fixed.addView(Button(this).apply {
            text = "轻点预览试触感 · 完整试打 ›"; textSize = 12f; isAllCaps = false
            minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0); stateListAnimator = null
            setTextColor(ui.palette.highlight); background = ui.palette.surface(ui.palette.key)
            contentDescription = "完整试打"
            setOnClickListener { startActivity(Intent(this@AppearanceActivity, TryInputActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, dp(34)))
        val tabs = LinearLayout(this).apply { isBaselineAligned = false }
        for ((id, title) in listOf("themes" to "主题", "feedback" to "按键反馈", "size" to "尺寸与文字")) {
            tabs.addView(Button(this).apply {
                text = title; textSize = 14f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                isSingleLine = true
                setHorizontallyScrolling(false)
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                stateListAnimator = null; isSelected = id == tab
                setTextColor(if (isSelected) ui.palette.onAccent else ui.palette.ink)
                background = ui.palette.surface(if (isSelected) ui.palette.accent else ui.palette.key, true)
                setOnClickListener {
                    if (tab != id) { positions[tab] = ui.scroll.scrollY; tab = id; showPage(positions[id] ?: 0) }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        fixed.addView(tabs)
        ui.fixedPreview = fixed
        when (tab) { "feedback" -> feedbackPage(); "size" -> sizePage(); else -> themesPage() }
        ui.show("键盘与外观") { finish() }
        ui.scroll.post { ui.scroll.scrollTo(0, y) }
    }
    private fun slider(title: String, unit: String, min: Int, max: Int, value: Int, enabled: Boolean = true, change: (Int) -> Unit) {
        val caption = ui.label("$title：$value $unit", 16f, false)
        ui.box.addView(SeekBar(this).apply {
            this.max = max - min; progress = value - min; contentDescription = title; isEnabled = enabled
            minimumHeight = dp(44)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.palette.highlight)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.palette.highlight)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) { change(progress + min); caption.text = "$title：${progress + min} $unit" }
                }
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }
    private fun picker(title: String, choices: Map<String, String>, selected: String, enabled: Boolean = true, change: (String) -> Unit) {
        ui.label(title, 15f, false)
        val keys = choices.keys.toList()
        var current = selected
        ui.box.addView(Spinner(this).apply {
            contentDescription = title; isEnabled = enabled; minimumHeight = dp(48)
            adapter = object : ArrayAdapter<String>(this@AppearanceActivity, android.R.layout.simple_spinner_dropdown_item, choices.values.toList()) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = super.getView(position, convertView, parent).apply { (this as TextView).setTextColor(ui.palette.ink) }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = super.getDropDownView(position, convertView, parent).apply { (this as TextView).setTextColor(ui.palette.ink); setBackgroundColor(ui.palette.key) }
            }
            setSelection(keys.indexOf(selected).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val next = keys[position]
                    if (next != current) { current = next; change(next) }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }
    private fun themesPage() {
        val resolved = ui.palette.themeId
        ui.label("当前：${KeyboardThemes.name(resolved)}", 16f, false)
        for (pair in KeyboardThemes.names.keys.chunked(2)) {
            val row = LinearLayout(this)
            for (id in pair) row.addView(ThemeCard(this, id, resolved == id).apply {
                setOnClickListener { appearance = appearance.copy(theme = id); saveAppearance(true) }
            }, LinearLayout.LayoutParams(0, dp(108), 1f))
            ui.box.addView(row)
        }
        ui.toggle("显示键帽边界", "关闭后普通键与背景融为一体，功能键仍保留区分。", appearance.keyBorders) {
            appearance = appearance.copy(keyBorders = it); saveAppearance()
        }
        ui.toggle("跟随系统深浅色", "按手机的深浅色模式自动切换日间与夜间主题。", appearance.theme == "system") {
            appearance = appearance.copy(theme = if (it) "system" else resolved); saveAppearance(true)
        }
        if (appearance.theme == "system") {
            fun choices(selected: String) = LinkedHashMap(KeyboardThemes.names).apply {
                if (selected == "dark") put("dark", KeyboardThemes.name("dark"))
            }
            picker("日间主题", choices(appearance.dayTheme), appearance.dayTheme) { appearance = appearance.copy(dayTheme = it); saveAppearance(true) }
            picker("夜间主题", choices(appearance.nightTheme), appearance.nightTheme) { appearance = appearance.copy(nightTheme = it); saveAppearance(true) }
        }
        ui.label("Catppuccin for FlorisBoard / Nord：MIT 配色；键位沿用已确认方案。", 12f)
        ui.button("来源与许可") {
            val text = assets.open("licenses/themes.txt").bufferedReader().use { it.readText() }
            android.app.AlertDialog.Builder(this).setTitle("主题来源与许可").setMessage(text).setPositiveButton("关闭", null).show()
        }
    }
    private fun feedbackPage() {
        val settings = feedback.settings
        ui.section("触感")
        ui.toggle("按键震动", "轻点上方键帽试触感。", settings.vibrate) { feedback.settings = feedback.settings.copy(vibrate = it); saveFeedback(true) }
        ui.toggle("震动跟随系统", "采用手机系统的键盘触感；关闭后可选择强度。", settings.followSystem, settings.vibrate) {
            feedback.settings = feedback.settings.copy(followSystem = it); saveFeedback(true)
        }
        picker("震动强度", linkedMapOf("1" to "轻柔", "2" to "适中", "3" to "明显"), settings.strength.toString(), settings.vibrate && !settings.followSystem) {
            feedback.settings = feedback.settings.copy(strength = it.toInt()); saveFeedback()
            feedback.press(preview, sound = false)
        }
        ui.label(when {
            !settings.vibrate -> "先开启按键震动，再关闭跟随系统即可调整强度。"
            settings.followSystem -> "当前强度由手机系统控制；关闭跟随系统后可自行选择。"
            else -> "强度已即时保存；切换时试振一次，也可轻点上方键帽试触感。"
        }, 12f)
        ui.section("声音")
        ui.toggle("按键音效", "选择音色后，可分别试听字母、退格与操作键。", settings.sound) { feedback.settings = feedback.settings.copy(sound = it); saveFeedback(true) }
        picker("按键音色", FeedbackSettings.tones, settings.tone, settings.sound) {
            feedback.settings = feedback.settings.copy(tone = it); saveFeedback()
            feedback.press(preview, "letter")
        }
        slider("按键音量", "%", 0, 100, settings.volume, settings.sound) { feedback.settings = feedback.settings.copy(volume = it); saveFeedback() }
        ui.label(if (settings.sound) "此处调整应用键音；最终响度仍受手机系统音量控制。系统键音因手机而异。" else "开启按键音效后可调整音量并试听。", 12f)
        val samples = LinearLayout(this)
        for ((label, kind) in listOf("字母" to "letter", "退格" to "delete", "操作" to "action")) samples.addView(KeyboardKey(this).apply {
            text = "试按$label"; textSize = 14f; contentDescription = "反馈试按 $kind"; feedbackKind = kind
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0); setTextColor(ui.palette.ink)
            background = ui.palette.surface(ui.palette.function, true); setOnClickListener { }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        feedback.bind(samples); ui.box.addView(samples)
        ui.label("静音／勿扰时不播放键音；语音录制时停用键音。实际震感与音色因手机而异。", 12f)
        ui.button("恢复默认反馈") { feedback.settings = FeedbackSettings(); saveFeedback(true) }
    }
    private fun sizePage() {
        fun option(title: String, description: String, checked: Boolean, rebuild: Boolean = false, change: (Boolean) -> Unit) {
            ui.toggle(title, description, checked) { change(it); saveAppearance(rebuild) }
        }
        fun size(title: String, unit: String, min: Int, max: Int, value: Int, enabled: Boolean = true, change: (Int) -> Unit) {
            slider(title, unit, min, max, value, enabled) { change(it); saveAppearance() }
        }
        ui.section("键盘尺寸")
        option("按屏宽自动调整高度", "竖屏行高随屏宽调整；关闭后使用手动行高。", appearance.autoHeight, true) { appearance = appearance.copy(autoHeight = it) }
        size("竖屏按键行高", "dp", 44, 66, appearance.rowHeight, !appearance.autoHeight) { appearance = appearance.copy(rowHeight = it) }
        size("横屏按键行高", "dp", 30, 42, appearance.landscapeHeight) { appearance = appearance.copy(landscapeHeight = it) }
        size("额外底部留白", "dp", 0, 36, appearance.bottomSpace) { appearance = appearance.copy(bottomSpace = it) }
        ui.label("已自动避让系统手势区；预览底边显示额外留白。", 12f)
        size("按键间距", "dp", 3, 7, appearance.keyGap) { appearance = appearance.copy(keyGap = it) }
        size("按键圆角", "dp", 4, 12, appearance.keyRadius) { appearance = appearance.copy(keyRadius = it) }
        ui.section("底行与副符号")
        option("符号与数字分开", "底行显示「符号 / 123」；关闭后为「?123 / 表情」。", appearance.splitBottom) { appearance = appearance.copy(splitBottom = it) }
        option("字母副符号", "显示副符号，长按仍可选择大小写。", appearance.auxiliary) { appearance = appearance.copy(auxiliary = it) }
        option("上滑输入副符号", "从字母键向上滑输入。", appearance.swipeSymbols) { appearance = appearance.copy(swipeSymbols = it) }
        option("长按显示备选", "选择大小写及副符号；横屏仍可使用。", appearance.longPressSymbols) { appearance = appearance.copy(longPressSymbols = it) }
        option("独立数字行", "竖屏增加一行数字；横屏和注音不增加。", appearance.numberRow) { appearance = appearance.copy(numberRow = it) }
        ui.section("文字大小")
        size("按键字号", "sp", 18, 30, appearance.keySize) { appearance = appearance.copy(keySize = it) }
        size("中文候选字号", "sp", 18, 30, appearance.candidateSize) { appearance = appearance.copy(candidateSize = it) }
        size("英文释义字号", "sp", 11, 18, appearance.glossSize) { appearance = appearance.copy(glossSize = it) }
        ui.label("自动保存。完整试打可查看实际高度；返回后保留滚动位置。恢复外观不改变反馈、词库或学习数据。", 12f)
        ui.button("恢复默认外观") { appearance = KeyboardAppearance(); saveAppearance(true) }
    }
}
