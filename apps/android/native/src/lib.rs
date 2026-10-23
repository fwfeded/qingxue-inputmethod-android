//! 安卓 JNI 入口；引擎和候选仅由同一个工作线程访问。

pub mod dictionaries;
pub mod domains;
pub mod learning_backup;
pub mod phrases;
pub mod session;

#[cfg(target_os = "android")]
mod bridge;
