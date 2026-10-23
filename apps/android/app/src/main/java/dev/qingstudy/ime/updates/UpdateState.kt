//! 更新页面可观察状态；不混入输入法引擎和学习配置。
package dev.qingstudy.ime

data class UpdateState(val phase: String = "idle", val message: String = "检查是否有新版本", val release: UpdateRelease? = null, val percent: Int = 0)
