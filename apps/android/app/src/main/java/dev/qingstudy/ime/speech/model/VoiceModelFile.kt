//! 固定官方模型文件；大小与散列共同约束下载内容。
package dev.qingstudy.ime

internal data class VoiceModelFile(val name: String, val bytes: Long, val sha256: String, val url: String)
