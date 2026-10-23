//! 原生文字运行时只由独立服务加载；UTF-8 字节传递避免 JNI 字符编码损坏。
package dev.qingstudy.ime

internal object TextNative {
    init { System.loadLibrary("qingstudy_text") }
    external fun select(id: Long)
    external fun generate(path: ByteArray, prompt: ByteArray, id: Long): ByteArray
    external fun generateConstrained(path: ByteArray, prompt: ByteArray, grammar: ByteArray, id: Long): ByteArray
    external fun release()
}
