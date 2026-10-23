// 原生设置页的标题、分类行与安全区；颜色跟随键盘外观。
package dev.qingstudy.ime

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsUi(private val activity: Activity) {
    val palette = KeyboardPalette(activity, KeyboardAppearance.read(activity))
    private val paper = palette.key
    var fixedPreview: View? = null
    val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(20)) }
    val scroll = ScrollView(activity).apply { isFillViewport = true; addView(box) }
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun label(value: String, size: Float = 14f, muted: Boolean = true): TextView = TextView(activity).apply {
        text = value; textSize = size; setTextColor(if (muted) palette.muted else palette.ink)
        setPadding(0, dp(10), 0, dp(12)); box.addView(this)
    }
    fun section(title: String) { label(title, 13f, false).setTextColor(palette.highlight) }
    fun button(title: String, primary: Boolean = false, click: () -> Unit): Button = Button(activity).apply {
        text = title; textSize = 15f; isAllCaps = false; minHeight = dp(48)
        stateListAnimator = null; elevation = 0f
        setTextColor(if (primary) palette.onAccent else palette.highlight); background = palette.surface(if (primary) palette.accent else paper, true)
        setOnClickListener { click() }; box.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun row(title: String, summary: String, icon: String = "", compact: Boolean = false, click: () -> Unit) {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(if (compact) 65 else 72); setPadding(0, dp(8), 0, dp(8))
            background = palette.surface(if (compact) palette.key else paper); isFocusable = true
            setOnClickListener { click() }
        }
        if (icon.isNotEmpty()) row.addView(TextView(activity).apply {
            text = icon; textSize = 18f; gravity = Gravity.CENTER; setTextColor(palette.onAccent)
            background = palette.surface(palette.accent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(activity).apply { text = title; textSize = if (compact) 14f else 17f; setTextColor(palette.ink) })
        copy.addView(TextView(activity).apply { text = summary; textSize = if (compact) 11f else 12f; setTextColor(palette.muted); setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply { text = "›"; textSize = 22f; setTextColor(palette.muted); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
        box.addView(View(activity).apply { setBackgroundColor(palette.function) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    fun toggle(title: String, summary: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit): Switch {
        val toggle = Switch(activity).apply {
            text = title; textSize = 16f; minHeight = dp(48); isChecked = checked; isEnabled = enabled
            setPadding(0, dp(8), 0, dp(8)); setTextColor(palette.ink)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.highlight, palette.muted))
            setOnCheckedChangeListener { _, value -> change(value) }
        }
        box.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        if (summary.isNotEmpty()) label(summary, 12f)
        return toggle
    }
    fun styleContents(view: View = box) {
        if (view is TextView) view.setTextColor(palette.ink)
        if (view is Button) {
            view.stateListAnimator = null; view.elevation = 0f
            view.textSize = 15f; view.isAllCaps = false; view.minHeight = dp(48)
            view.background = palette.surface(palette.accent, true)
            view.setTextColor(palette.onAccent)
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) styleContents(view.getChildAt(index))
            view.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { child?.let { styleContents(it) } }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }
    }
    fun show(title: String, back: (() -> Unit)? = null) {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(18), dp(4)) }
        if (back != null) header.addView(Button(activity).apply {
            text = "‹"; textSize = 28f; contentDescription = "返回上一级"
            setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; setTextColor(palette.ink)
            stateListAnimator = null; elevation = 0f
            background = palette.surface(paper); setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(TextView(activity).apply {
            text = title; textSize = if (back == null) 23f else 24f; setTextColor(palette.ink)
            if (back == null) { setPadding(dp(8), 0, 0, 0); setTypeface(null, android.graphics.Typeface.BOLD) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (back == null) header.addView(TextView(activity).apply {
            text = "青"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(palette.onAccent); background = palette.surface(palette.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(header, LinearLayout.LayoutParams(-1, if (back == null) dp(58) else -2))
        val fixed = fixedPreview
        if (fixed != null && activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val body = LinearLayout(activity).apply { isBaselineAligned = false }
            body.addView(fixed, LinearLayout.LayoutParams(0, -1, .42f))
            body.addView(scroll, LinearLayout.LayoutParams(0, -1, .58f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            fixed?.let { root.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        activity.window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        activity.window.navigationBarColor = paper
        activity.setContentView(root)
    }
}
