// JNI 声明；会话调用固定在各自引擎线程，文件操作由独立后台队列执行。
package dev.qingstudy.ime

object NativeBridge {
    external fun textLight(source: String, outputs: String, complete: Boolean): String
    external fun textTool(source: String, output: String, complete: Boolean): String
    external fun voiceText(text: String, options: String, finalResult: Boolean): String
    init { System.loadLibrary("qingjian_android") }
    @JvmStatic external fun create(directory: String): Long
    @JvmStatic external fun step(handle: Long, action: String, argument: String): String
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun dictionaries(directory: String, action: String, argument: String): String
    @JvmStatic external fun exportLearning(directory: String, destination: String): String
    @JvmStatic external fun learning(directory: String, action: String, source: String): String
    @JvmStatic external fun phrases(source: String): String
    @JvmStatic external fun phraseFile(action: String, source: String): String
    @JvmStatic external fun domains(directory: String): String
}
