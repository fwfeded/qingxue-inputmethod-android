//! 每次音频会话拥有独立音色库，快速离开/返回设置不误释放新会话。
package dev.qingstudy.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

internal class PreloadedKeySounds(private val context: Context, private val attributes: AudioAttributes) : AutoCloseable {
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<String, Int>()
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    fun load() {
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build().also { player ->
            player.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
            for (tone in listOf("soft", "crisp", "low")) for (kind in listOf("letter", "delete", "action")) {
                context.assets.openFd("feedback/${tone}_$kind.wav").use { samples["$tone:$kind"] = player.load(it, 1) }
            }
        }
    }
    fun play(tone: String, kind: String, gain: Float) { samples["$tone:$kind"]?.takeIf { it in loaded }?.let { pool?.play(it, gain, gain, 1, 0, 1f) } }
    override fun close() { pool?.release(); pool = null; samples.clear(); loaded.clear() }
}
