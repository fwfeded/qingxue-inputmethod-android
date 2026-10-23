// R3 首页：参考 FlorisBoard 的状态卡和分类导航，实际状态来自系统。
package dev.qingstudy.ime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val updateState: (UpdateState) -> Unit = { if (!isFinishing) showHome() }
    private val imeState = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if (!isFinishing) showHome() }
    }
    override fun onStart() {
        super.onStart()
        for (key in listOf(Settings.Secure.DEFAULT_INPUT_METHOD, Settings.Secure.ENABLED_INPUT_METHODS)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, imeState)
        }
        UpdateManager.observe(updateState)
    }
    override fun onStop() { UpdateManager.forget(updateState); contentResolver.unregisterContentObserver(imeState); super.onStop() }
    override fun onResume() { super.onResume(); showHome(); UpdateManager.autoCheck(this) }
    private fun settings(page: String = "home") {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page))
    }
    private fun showHome() {
        val ui = SettingsUi(this)
        val palette = ui.palette
        val imm = getSystemService(InputMethodManager::class.java)
        val component = ComponentName(this, PrototypeIme::class.java)
        val enabled = imm.enabledInputMethodList.any { ComponentName(it.packageName, it.serviceName) == component }
        val selected = ComponentName.unflattenFromString(Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "") == component
        ui.label("打中文，也认识一个英文词。", 13f).setPadding(0, 0, 0, ui.dp(18))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16))
            background = GradientDrawable().apply { setColor(palette.accent); cornerRadius = ui.dp(20).toFloat() }
        }
        fun cardText(copy: String, size: Float, bold: Boolean = false) {
            card.addView(TextView(this).apply {
                text = copy; textSize = size; setTextColor(palette.onAccent); setPadding(0, ui.dp(6), 0, ui.dp(6))
                if (bold) setTypeface(null, Typeface.BOLD)
            })
        }
        cardText(when { !enabled -> "○ 尚未启用"; !selected -> "● 已启用 · 尚未选中"; else -> "● 已启用 · 当前输入法" }, 12f)
        cardText(when { !enabled -> "先启用青学键盘"; !selected -> "还差一步，选中青学"; else -> "可以开始打字了" }, 21f, true)
        cardText(if (selected) "中文候选与英文释义\n试打一句，找到舒服的输入节奏。" else "跟随系统提示完成设置，随后即可试打。", 12f)
        card.addView(Button(this).apply {
            text = when { !enabled -> "去启用"; !selected -> "选择青学"; else -> "试用键盘" }
            textSize = 14f; typeface = Typeface.DEFAULT; isAllCaps = false; stateListAnimator = null; minHeight = ui.dp(48)
            setTextColor(palette.ink)
            background = palette.surface(palette.key)
            setOnClickListener { when {
                !enabled -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                !selected -> imm.showInputMethodPicker()
                else -> startActivity(Intent(this@MainActivity, TryInputActivity::class.java))
            } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        ui.box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
        val shortcuts = LinearLayout(this)
        for ((index, item) in listOf("⌨\n键盘外观", "▤\n管理词库", "Aa\n学习设置").withIndex()) {
            shortcuts.addView(Button(this).apply {
                text = item; textSize = 12f; typeface = Typeface.DEFAULT; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)); setTextColor(palette.highlight)
                stateListAnimator = null; background = palette.surface(palette.key, true)
                contentDescription = item.substringAfter('\n')
                setOnClickListener { when (index) {
                    0 -> startActivity(Intent(this@MainActivity, AppearanceActivity::class.java))
                    1 -> settings("dictionary")
                    else -> settings("learning")
                } }
            }, LinearLayout.LayoutParams(0, ui.dp(84), 1f))
        }
        ui.box.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
        val firstLink = ui.box.childCount
        ui.row("全部设置", "输入、候选、词库与学习", compact = true) { settings() }
        val prefs = Preferences.read(this)
        val schemes = mapOf("pinyin" to "全拼", "zhuyin" to "大千注音", "xiaohe" to "小鹤双拼", "ziranma" to "自然码", "microsoft" to "微软双拼", "sogou" to "搜狗双拼", "abc" to "智能ABC", "xiaolang" to "小浪双拼", "shoudao" to "首道双拼", "jiajia" to "拼音加加")
        val script = if (prefs.optString("traditional", "off") == "off") "简体" else "繁体"
        ui.row("输入方案", "${schemes[prefs.optString("scheme", "pinyin")] ?: "全拼"} · $script", compact = true) { settings("input") }
        val available = UpdateManager.state.release
        ui.row("关于与帮助", if (available != null) "可更新至 ${available.name}" else "青学输入法 · 开源项目", compact = true) { settings("about") }
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = GradientDrawable().apply { setColor(palette.key); cornerRadius = ui.dp(16).toFloat(); setStroke(ui.dp(1), palette.function) }
        }
        repeat(ui.box.childCount - firstLink) { val child = ui.box.getChildAt(firstLink); ui.box.removeView(child); links.addView(child) }
        links.removeViewAt(links.childCount - 1)
        ui.box.addView(links)
        ui.label("离线输入 · 学习记录默认关闭", 11f).gravity = Gravity.CENTER
        ui.show("青学")
    }
}

