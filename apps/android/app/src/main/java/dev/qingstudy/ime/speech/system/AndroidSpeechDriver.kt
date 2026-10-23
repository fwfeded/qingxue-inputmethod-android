//! Android 识别器薄适配；调用方保证创建、调用与销毁均发生在主线程。
package dev.qingstudy.ime

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

internal class AndroidSpeechDriver(context: Context, listener: RecognitionListener) : SystemSpeechDriver {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    init {
        try { recognizer.setRecognitionListener(listener) }
        catch (error: Exception) { runCatching { recognizer.destroy() }; throw error }
    }

    override fun start(intent: Intent) { recognizer.startListening(intent) }
    override fun stop() { recognizer.stopListening() }
    override fun cancel() { recognizer.cancel() }
    override fun close() { recognizer.destroy() }
}
