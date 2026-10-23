//! 安卓学习表的一致性快照；输入保存与导出共用跨进程锁。
use serde_json::{Map, Value, json};
use std::fs::{File, OpenOptions};
use std::io::Read;
use std::path::Path;
mod document;
mod generations;
mod tables;
mod validation;
pub use generations::{active_directory, clear_habits, restore};

const TABLES: [&str; 8] = [
    "frequency.tsv",
    "user-words.tsv",
    "user-ngram.tsv",
    "user-choices.tsv",
    "user-english.tsv",
    "user-typos.tsv",
    "vocabulary.tsv",
    "usage.tsv",
];
const MAX_FILE: u64 = 4 * 1024 * 1024;
const MAX_TOTAL: usize = 16 * 1024 * 1024;

pub fn inspect(source: &Path) -> Result<Value, String> {
    validation::read(source).map(|(_, result)| result)
}

pub(crate) fn lock(directory: &Path) -> Result<File, String> {
    std::fs::create_dir_all(directory).map_err(|e| e.to_string())?;
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(directory.join(".snapshot.lock"))
        .map_err(|e| e.to_string())?;
    #[cfg(not(target_os = "android"))]
    file.lock().map_err(|e| e.to_string())?;
    // std::fs::File::lock 在当前 Android std 中返回 Unsupported；bionic 支持 flock。
    #[cfg(target_os = "android")]
    {
        use std::os::fd::AsRawFd;
        loop {
            // fd 由此处存活的 File 独占持有；关闭 File 会释放锁。
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
                break;
            }
            let error = std::io::Error::last_os_error();
            if error.kind() != std::io::ErrorKind::Interrupted {
                return Err(error.to_string());
            }
        }
    }
    Ok(file)
}

pub fn export(directory: &Path, destination: &Path) -> Result<Value, String> {
    let guard = lock(directory)?;
    let directory = active_directory(directory)?;
    let mut files = Map::new();
    let mut total = 0;
    let mut count = 0;
    for name in TABLES {
        let file = match File::open(directory.join(name)) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                files.insert(name.into(), Value::Null);
                continue;
            }
            Err(error) => return Err(format!("无法读取学习表 {name}：{error}")),
        };
        let mut bytes = Vec::new();
        file.take(MAX_FILE + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        total += bytes.len();
        if bytes.len() as u64 > MAX_FILE || total > MAX_TOTAL {
            return Err("学习备份超过限制：单表 4 MB、合计 16 MB".into());
        }
        let text = String::from_utf8(bytes)
            .map_err(|_| format!("学习表 {name} 不是有效 UTF-8，未生成备份"))?;
        files.insert(name.into(), Value::String(text));
        count += 1;
    }
    // 只在读取本机表时持锁，序列化和系统文档写入不能阻塞输入保存。
    drop(guard);
    let created = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs();
    let snapshot = json!({"format":"qingstudy-learning-backup", "version":1,
        "createdUnixSeconds":created, "files":files});
    qingjian_core::storage::write_atomic_str(destination, &snapshot.to_string())
        .map_err(|e| e.to_string())?;
    Ok(json!({"files":count,"bytes":total}))
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod restore_tests;
