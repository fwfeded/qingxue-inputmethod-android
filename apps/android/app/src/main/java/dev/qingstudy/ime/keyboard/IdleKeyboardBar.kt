// R3 空闲工具与标点区；与组合文字、候选区等高替换。
package dev.qingstudy.ime

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class IdleKeyboardBar(context: Context, palette: KeyboardPalette, height: Int, action: (String, String) -> Unit) : LinearLayout(context) {
    private var voiceLabel: TextView? = null
    fun voiceProvider(provider: String) {
        val label = when (provider) { "online" -> "电脑语音"; "offline" -> "离线语音"; else -> "手机语音" }
        voiceLabel?.let { if (it.text.toString() != label) {
            it.text = label
            if (android.os.Build.VERSION.SDK_INT >= 30) (it.parent as? android.view.View)?.stateDescription = label
        } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        val tools = LinearLayout(context)
        val entries = listOf(Triple("☷", "工具", "menu"), Triple("123", "数字", "numbers"), Triple("!?#", "符号", "symbols"),
            Triple("☺︎", "表情", "emoji"), if (VoiceSettings.read(context).optBoolean("entry", true)) Triple("♩", "语音", "voice") else Triple("Aa", "外观", "appearance"), Triple("⚙", "设置", "settings"), Triple("⌄", "收起", "hide"))
        for ((icon, label, key) in entries) {
            tools.addView(LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                contentDescription = when (key) { "numbers" -> "工具 numbers"; "hide" -> "收起键盘"; else -> "工具 $key" }
                isFocusable = true; background = palette.surface(palette.background)
                addView(TextView(context).apply {
                    text = icon; maxLines = 1; setHorizontallyScrolling(false)
                    if (key == "voice") { text = ""; setCompoundDrawablesWithIntrinsicBounds(null, context.getDrawable(android.R.drawable.ic_btn_speak_now)?.apply { setTint(palette.muted) }, null, null) }
                    gravity = Gravity.CENTER; setTextColor(palette.muted)
                    setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(palette.key) }; includeFontPadding = false
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(30), dp(28)))
                addView(TextView(context).apply {
                    text = label; textSize = 10f; gravity = Gravity.CENTER; setTextColor(palette.ink)
                    if (key == "voice") {
                        voiceLabel = this; maxLines = 1
                        setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnClickListener { action(key, "") }
            }, LayoutParams(0, dp(height - 32), 1f))
        }
        addView(tools)
        voiceProvider(VoiceSettings.read(context).optString("provider", "system"))
        val punctuation = LinearLayout(context)
        listOf("，" to ",", "。" to ".", "？" to "?", "！" to "!", "、" to "\\", "…" to "…", "：" to ":", "；" to ";").forEach { (label, value) ->
            punctuation.addView(Button(context).apply {
                text = label; textSize = 19f; typeface = android.graphics.Typeface.DEFAULT; contentDescription = "标点 $label"
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0); includeFontPadding = false; stateListAnimator = null
                setTextColor(palette.muted); background = palette.surface(palette.background)
                setOnClickListener { action("punctuate", value) }
            }, LayoutParams(0, dp(32), 1f))
        }
        addView(punctuation)
    }
}
