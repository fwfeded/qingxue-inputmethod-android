//! 仅显式调试 IPC 可使用的 WAV 输入；仍经同一服务、VAD 和真实识别器。
package dev.qingstudy.ime

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

internal class FixtureAudioSource(private val file: File) : VoiceAudioSource {
    private val closed = AtomicBoolean(false)
    private val gate = Any()
    private var input: RandomAccessFile? = null
    private var remaining = 0L
    private var nextReadAt = 0L

    override fun start() = synchronized(gate) {
        check(BuildConfig.DEBUG && !closed.get()) { "测试音源不可用" }
        val source = RandomAccessFile(file, "r")
        input = source
        try {
            check(source.readInt() == 0x52494646) { "测试音频不是 WAV" }
            source.skipBytes(4)
            check(source.readInt() == 0x57415645) { "测试音频不是 WAVE" }
            var validFormat = false
            while (source.filePointer + 8 <= source.length()) {
                val tag = source.readInt()
                val size = Integer.reverseBytes(source.readInt()).toLong() and 0xffffffffL
                val offset = source.filePointer
                check(size <= source.length() - offset) { "测试音频长度无效" }
                if (tag == 0x666d7420) {
                    check(size >= 16) { "测试音频格式无效" }
                    val format = java.lang.Short.reverseBytes(source.readShort()).toInt()
                    val channels = java.lang.Short.reverseBytes(source.readShort()).toInt()
                    val rate = Integer.reverseBytes(source.readInt())
                    source.skipBytes(6)
                    val bits = java.lang.Short.reverseBytes(source.readShort()).toInt()
                    validFormat = format == 1 && channels == 1 && rate == 16000 && bits == 16
                } else if (tag == 0x64617461) {
                    check(validFormat && size in 2..1920000 && size % 2 == 0L) { "测试音频需为 1–60 秒 16kHz 单声道 PCM16" }
                    remaining = size
                    nextReadAt = SystemClock.elapsedRealtime()
                    return@synchronized
                }
                source.seek(offset + size + size % 2)
            }
            error("测试音频缺少 PCM 数据")
        } catch (failure: Throwable) { close(); throw failure }
    }

    override fun read(destination: FloatArray): Int {
        val count = synchronized(gate) {
            if (closed.get() || remaining == 0L) return -1
            val source = input ?: return -1
            val count = minOf(destination.size.toLong(), remaining / 2).toInt()
            repeat(count) { destination[it] = java.lang.Short.reverseBytes(source.readShort()) / 32768f }
            remaining -= count * 2
            nextReadAt += count * 1000 / 16000
            count
        }
        // 与麦克风同速，端到端长按测试可在松手前观察真实 partial；睡眠不持有关闭锁。
        val wait = nextReadAt - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(minOf(wait, 40L))
        return if (closed.get()) -1 else count
    }

    override fun close() = synchronized(gate) {
        if (closed.compareAndSet(false, true)) { input?.close(); input = null }
    }
}
