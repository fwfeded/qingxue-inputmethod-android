//! 经过签名索引验证的单 ABI 安装包描述。
package dev.qingstudy.ime

data class UpdateRelease(
    val code: Long,
    val name: String,
    val channel: String,
    val abi: String,
    val minSdk: Int,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val certificate: String,
    val notes: String,
)
