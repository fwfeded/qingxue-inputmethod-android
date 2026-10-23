//! 学习记录按代切换；唯一的 active 指针发布后，新会话才能读到新数据。
use serde_json::Value;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

pub fn active_directory(root: &Path) -> Result<PathBuf, String> {
    let mut name = String::new();
    match File::open(root.join("active-generation")) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(root.to_owned()),
        Err(error) => return Err(error.to_string()),
        Ok(file) => {
            file.take(128)
                .read_to_string(&mut name)
                .map_err(|e| e.to_string())?;
        }
    }
    if !name.starts_with("restore-")
        || name.len() > 100
        || !name.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')
    {
        return Err("学习记录目录指针无效".into());
    }
    let path = root.join("generations").join(name);
    if !path.is_dir() {
        return Err("学习记录目录不存在".into());
    }
    Ok(path)
}

pub fn restore(root: &Path, source: &Path) -> Result<Value, String> {
    let (files, result) = super::validation::read(source)?;
    let _guard = super::lock(root)?;
    let previous = active_directory(root)?;
    let generations = root.join("generations");
    std::fs::create_dir_all(&generations).map_err(|e| e.to_string())?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let name = format!("restore-{}-{stamp}", std::process::id());
    let path = generations.join(&name);
    std::fs::create_dir(&path).map_err(|e| e.to_string())?;
    // 未发布的目录即使写入失败也不会被输入法读取；保留用于排查，不覆盖旧记录。
    for table in super::TABLES {
        if let Some(text) = files[table].as_str() {
            let mut file = File::create(path.join(table)).map_err(|e| e.to_string())?;
            file.write_all(text.as_bytes())
                .and_then(|()| file.sync_all())
                .map_err(|e| e.to_string())?;
        }
    }
    // 保存旧代位置便于人工恢复。旧表保持原状，下一次导出只读取当前代。
    write_summary(&path)?;
    qingjian_core::storage::write_atomic_str(
        &path.join("previous-directory"),
        &previous.to_string_lossy(),
    )
    .map_err(|e| e.to_string())?;
    qingjian_core::storage::write_atomic_str(&root.join("active-generation"), &name)
        .map_err(|e| e.to_string())?;
    Ok(result)
}

fn write_summary(path: &Path) -> Result<(), String> {
    use qingjian_core::{Language, UsageMeter, VocabularyTracker};
    let usage = qingjian_learning::UsageStats::open(path.join("usage.tsv")).summary();
    let words = qingjian_learning::VocabularyBook::open(path.join("vocabulary.tsv"))
        .summary(Language::English);
    let value = serde_json::json!({"today":usage.today.hanzi,"total":usage.total.hanzi,
        "english":usage.total.english_words,"days":usage.days,"seen":words.seen,
        "familiar":words.familiar,"committed":words.committed,"used":words.used});
    qingjian_core::storage::write_atomic_str(&path.join("summary.json"), &value.to_string())
        .map_err(|e| e.to_string())
}

/// 重置当前输入习惯，保留词汇/统计；代指针防止旧 IME 回写已清除记录。
pub fn clear_habits(root: &Path) -> Result<Value, String> {
    let _guard = super::lock(root)?;
    let previous = active_directory(root)?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let name = format!("restore-clear-{}-{stamp}", std::process::id());
    let path = root.join("generations").join(&name);
    std::fs::create_dir_all(&path).map_err(|e| e.to_string())?;
    for table in ["vocabulary.tsv", "usage.tsv"] {
        match std::fs::read(previous.join(table)) {
            Ok(bytes) => {
                let mut file = File::create(path.join(table)).map_err(|e| e.to_string())?;
                file.write_all(&bytes)
                    .and_then(|()| file.sync_all())
                    .map_err(|e| e.to_string())?;
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.to_string()),
        }
    }
    write_summary(&path)?;
    qingjian_core::storage::write_atomic_str(&root.join("active-generation"), &name)
        .map_err(|e| e.to_string())?;
    // 指针已切换，旧会话不会再写这些文件；只清除刚才活动代的六张习惯表。
    for table in &super::TABLES[..6] {
        match std::fs::remove_file(previous.join(table)) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(format!("当前习惯已重置，但旧表 {table} 清理失败：{e}")),
        }
    }
    Ok(serde_json::json!({"cleared":true}))
}
