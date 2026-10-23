// 设置存于解锁后可用的私有目录；跨进程每次重新读，避免 SharedPreferences 缓存。
package dev.qingstudy.ime
import android.content.Context
import android.os.UserManager
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object Preferences {
    fun read(context: Context): JSONObject {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return JSONObject()
        return runCatching { JSONObject(AtomicFile(File(context.filesDir, "settings.json")).openRead().bufferedReader().use { it.readText() }) }.getOrElse { JSONObject() }
    }
    fun save(context: Context, json: JSONObject) {
        val file = AtomicFile(File(context.filesDir, "settings.json"))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun engine(context: Context, privateInput: Boolean): String {
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        return read(context).put("directory", if (unlocked) File(context.filesDir, "learning").absolutePath else "")
            .put("private", privateInput || !unlocked).toString()
    }
}
