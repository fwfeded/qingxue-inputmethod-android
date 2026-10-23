//! Android 触感与三类系统键音；音频查询在专用线程，迟到的声音丢弃，不阻塞拼音输入。
package dev.qingstudy.ime

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

internal class AndroidFeedbackOutput(context: Context) : FeedbackOutput {
    private val app = context.applicationContext
    private val vibrator = app.getSystemService(Vibrator::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sounds: PreloadedKeySounds? = null

    override fun haptic(view: View, followSystem: Boolean, strength: Int) {
        runCatching {
            if (notifications.currentInterruptionFilter !in listOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN)) return
            if (followSystem) {
                if (Settings.System.getInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
                // 禁用控件默认长按触感，只让本模块调用；不忽略系统设置。
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            } else if (vibrator.hasVibrator()) {
                val level = strength.coerceIn(1, 3) - 1
                val duration = longArrayOf(8, 14, 22)[level]
                val amplitude = if (vibrator.hasAmplitudeControl()) intArrayOf(45, 90, 150)[level] else VibrationEffect.DEFAULT_AMPLITUDE
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude), attributes)
            }
        }
    }

    override fun prepare() {
        if (handler == null) {
            thread = HandlerThread("keyboard-sound").apply { start() }
            handler = Handler(thread!!.looper)
            val bank = PreloadedKeySounds(app, attributes)
            sounds = bank
            handler!!.post { runCatching {
                audio.loadSoundEffects()
                bank.load()
            } }
        }
    }
    override fun sound(kind: String, volume: Int, tone: String) {
        prepare()
        val requested = SystemClock.uptimeMillis()
        val bank = sounds
        handler!!.post {
            // 不追赶旧按键；静音、勿扰、系统音量为零和麦克风占用时均不播放。
            if (SystemClock.uptimeMillis() - requested > 100) return@post
            runCatching {
                if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.isStreamMute(AudioManager.STREAM_SYSTEM)) return@post
                if (notifications.currentInterruptionFilter !in listOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN)) return@post
                if (audio.mode != AudioManager.MODE_NORMAL || audio.activeRecordingConfigurations.isNotEmpty()) return@post
                val system = audio.getStreamVolume(AudioManager.STREAM_SYSTEM)
                if (system == 0) return@post
                val effect = when (kind) { "delete" -> AudioManager.FX_KEYPRESS_DELETE; "action" -> AudioManager.FX_KEYPRESS_RETURN; else -> AudioManager.FX_KEYPRESS_STANDARD }
                // SoundEffectsHelper / STREAM_SYSTEM 已应用系统音量，不能在应用端重复衰减。
                val gain = volume.coerceIn(0, 100) / 100f
                if (tone == "system") audio.playSoundEffect(effect, gain)
                else bank?.play(tone, kind, gain)
            }
        }
    }
    override fun close() {
        handler?.removeCallbacksAndMessages(null)
        // 在同一音频线程释放，避免切换标签时与加载/播放并发。
        val bank = sounds; sounds = null
        handler?.post { bank?.close() }
        thread?.quitSafely(); handler = null; thread = null
    }
}
