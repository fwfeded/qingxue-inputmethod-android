// 内置领域词库独立开关，名称和条数来自 Rust，数据不计入用户导入配额。
package dev.qingstudy.ime

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray

class DomainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = SettingsUi(this)
        val box = ui.box
        box.addView(Button(this).apply { text = "返回设置"; setOnClickListener { finish() } })
        box.addView(TextView(this).apply { text = getString(R.string.domain_summary); textSize = 16f })
        box.addView(Button(this).apply {
            text = "数据来源与许可"
            setOnClickListener {
                val license = assets.open("licenses/lexicon/00_meta/THUOCL_LICENSE.txt").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this@DomainActivity).setTitle("THUOCL · MIT")
                    .setMessage(license).setPositiveButton("关闭", null).show()
            }
        })
        val status = TextView(this).apply { text = "正在读取词库…"; textSize = 16f; contentDescription = "领域词库状态" }
        box.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        ui.styleContents()
        ui.show("内置领域词库") { finish() }
        val app = applicationContext
        DictionaryFiles.executor.execute {
            val result = runCatching { JSONArray(NativeBridge.domains(DomainFiles.prepare(app).absolutePath)) }
            runOnUiThread {
                if (!isDestroyed) result.onSuccess { rows ->
                    status.text = getString(R.string.domain_ready, rows.length())
                    val settings = Preferences.read(this)
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val id = row.getString("id")
                        val name = row.getString("name")
                        list.addView(Switch(this).apply {
                            text = if (row.getBoolean("available")) getString(R.string.domain_row, name, row.getInt("entries")) else getString(R.string.domain_unavailable, name)
                            contentDescription = "领域词库 $name"; textSize = 16f; minHeight = ui.dp(64)
                            isEnabled = row.getBoolean("available")
                            isChecked = settings.optJSONArray("domains")?.let { enabled -> (0 until enabled.length()).any { enabled.optString(it) == id } } ?: row.getBoolean("defaultEnabled")
                            setOnCheckedChangeListener { _, checked ->
                                val fresh = Preferences.read(this@DomainActivity)
                                val current = fresh.optJSONArray("domains") ?: JSONArray().put("idioms")
                                val selected = (0 until current.length()).map { current.getString(it) }.toMutableSet()
                                if (checked) selected.add(id) else selected.remove(id)
                                fresh.put("domains", JSONArray(selected.toList()))
                                Preferences.save(this@DomainActivity, fresh)
                                status.text = getString(R.string.domain_saved)
                            }
                        })
                    }
                }.onFailure { status.text = getString(R.string.domain_error, it.message) }
            }
        }
    }
}
