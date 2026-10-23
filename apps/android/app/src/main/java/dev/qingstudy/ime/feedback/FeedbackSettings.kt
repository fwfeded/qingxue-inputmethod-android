//! 按键反馈独立保存，升级默认关闭，不修改引擎及个人学习设置。
package dev.qingstudy.ime

import android.content.Context
import org.json.JSONObject

data class FeedbackSettings(
    val vibrate: Boolean = false,
    val followSystem: Boolean = true,
    val strength: Int = 2,
    val sound: Boolean = false,
    val volume: Int = 30,
    val tone: String = "system"
) {
    fun toJson() = JSONObject().put("vibrate", vibrate).put("followSystem", followSystem)
        .put("strength", strength).put("sound", sound).put("volume", volume).put("tone", tone)

    companion object {
        val tones = linkedMapOf("system" to "系统键音", "soft" to "轻触", "crisp" to "清脆", "low" to "低音")
        fun read(context: Context): FeedbackSettings {
            val json = Preferences.read(context).optJSONObject("feedback") ?: JSONObject()
            return FeedbackSettings(json.optBoolean("vibrate", false), json.optBoolean("followSystem", true),
                json.optInt("strength", 2).coerceIn(1, 3), json.optBoolean("sound", false), json.optInt("volume", 30).coerceIn(0, 100),
                json.optString("tone", "system").takeIf { it in tones } ?: "system")
        }
        fun save(context: Context, value: FeedbackSettings) {
            Preferences.save(context, Preferences.read(context).put("feedback", value.toJson()))
        }
    }
}
