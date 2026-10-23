//! 安卓私有附加词库的导入、导出与管理；文件解析复用上游词库模块。
use qingjian_dictionary::Dictionary;
use serde_json::{Value, json};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

mod metadata;

pub fn files(directory: &Path) -> Result<Vec<PathBuf>, String> {
    if !directory.exists() {
        return Ok(Vec::new());
    }
    let mut paths = std::fs::read_dir(directory)
        .map_err(|e| e.to_string())?
        .map(|e| e.map(|e| e.path()))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    paths.retain(|p| p.is_file() && p.extension().is_some_and(|e| e == "qj"));
    paths.sort();
    Ok(paths)
}

pub fn signature(directory: &Path) -> Result<String, String> {
    Ok(files(directory)?
        .iter()
        .map(|p| format!("{}:{}", p.display(), !p.with_extension("off").exists()))
        .collect::<Vec<_>>()
        .join("|"))
}

pub fn load(directory: &Path) -> Result<Vec<Dictionary>, String> {
    files(directory)?
        .into_iter()
        .filter(|p| !p.with_extension("off").exists())
        .map(|p| Dictionary::from_path(p).map_err(|e| e.to_string()))
        .collect()
}

fn info(path: &Path) -> Result<Value, String> {
    let dictionary = Dictionary::from_path(path).map_err(|e| e.to_string())?;
    let meta = dictionary.metadata();
    Ok(json!({"id":path.file_name().unwrap().to_string_lossy(),
        "name":meta.map(|m| m.name.as_str()).unwrap_or("用户词库"),
        "license":meta.map(|m| m.license.as_str()).unwrap_or(""),
        "entries":dictionary.len(),"enabled":!path.with_extension("off").exists()}))
}

pub fn manage(directory: &Path, action: &str, argument: &str) -> Result<Value, String> {
    let args: Value = serde_json::from_str(argument).map_err(|e| e.to_string())?;
    let dicts = directory.join("dicts");
    std::fs::create_dir_all(&dicts).map_err(|e| e.to_string())?;
    if action == "list" {
        return files(&dicts)?
            .iter()
            .map(|p| info(p))
            .collect::<Result<Vec<_>, _>>()
            .map(Value::Array);
    }
    if action == "import" {
        return import(
            &dicts,
            Path::new(args["path"].as_str().ok_or("missing source")?),
        );
    }
    let id = args["id"].as_str().ok_or("missing dictionary id")?;
    if !id.ends_with(".qj") || id.contains(['/', '\\', ':']) || id.starts_with('.') {
        return Err("invalid dictionary id".into());
    }
    let path = dicts.join(id);
    let dictionary = Dictionary::from_path(&path).map_err(|e| e.to_string())?;
    match action {
        "toggle" => {
            let enabled = args["enabled"].as_bool().ok_or("missing enabled flag")?;
            let marker = path.with_extension("off");
            if enabled && marker.exists() {
                std::fs::remove_file(marker).map_err(|e| e.to_string())?;
            } else if !enabled {
                qingjian_core::storage::write_atomic_str(&marker, "disabled")
                    .map_err(|e| e.to_string())?;
            }
            info(&path)
        }
        "remove" => {
            drop(dictionary);
            let removed = dicts.join("removed");
            std::fs::create_dir_all(&removed).map_err(|e| e.to_string())?;
            std::fs::rename(&path, removed.join(id)).map_err(|e| e.to_string())?;
            let _ = std::fs::remove_file(path.with_extension("off"));
            Ok(json!({"removed":id}))
        }
        "export" => {
            let destination = Path::new(args["path"].as_str().ok_or("missing destination")?);
            match args["format"].as_str() {
                Some("qj") => {
                    std::fs::copy(&path, destination).map_err(|e| e.to_string())?;
                }
                Some("tsv") => {
                    qingjian_core::storage::write_atomic(destination, |writer| {
                        if let Some(meta) = dictionary.metadata() {
                            writeln!(
                                writer,
                                "# qingjian-dictionary-metadata-v1: {}",
                                serde_json::to_string(meta)?
                            )?;
                        }
                        for entry in dictionary.entries() {
                            writeln!(
                                writer,
                                "{}\t{}\t{}",
                                entry.text, entry.pinyin, entry.frequency
                            )?;
                        }
                        Ok(())
                    })
                    .map_err(|e| e.to_string())?;
                }
                _ => return Err("unsupported export format".into()),
            }
            Ok(json!({"entries":dictionary.len()}))
        }
        _ => Err("unknown dictionary operation".into()),
    }
}

fn import(dicts: &Path, source: &Path) -> Result<Value, String> {
    let size = std::fs::metadata(source).map_err(|e| e.to_string())?.len();
    if size > 16 * 1024 * 1024 {
        return Err("单本词库最大 16 MB".into());
    }
    let existing = files(dicts)?;
    let total: u64 = existing
        .iter()
        .filter_map(|p| p.metadata().ok())
        .map(|m| m.len())
        .sum();
    if existing.len() >= 32 || total + size > 64 * 1024 * 1024 {
        return Err("用户词库上限为 32 本、共 64 MB".into());
    }
    // Rime 上游会忽略跨文件和自定义列；手机必须明确拒绝，避免误报完整导入。
    if let Ok(text) = std::fs::read_to_string(source)
        && qingjian_dictionary::import::looks_like_rime(&text)
    {
        for line in text.lines().take_while(|line| line.trim() != "...") {
            if line.trim_start().starts_with("import_tables:")
                || line.trim_start().starts_with("columns:")
            {
                return Err(
                    "请先展开 Rime 合表，按词、拼音、词频三列导入；不支持 import_tables 或 columns"
                        .into(),
                );
            }
        }
    }
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let id = format!(
        "{}-{}-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|e| e.to_string())?
            .as_nanos(),
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    );
    let staging = dicts.join(format!(".import-{id}"));
    let result = (|| {
        let mut imported =
            qingjian_dictionary::import::import(source, &staging).map_err(|e| e.to_string())?;
        imported.path = metadata::restore(source, &imported.path)?;
        if imported.entries > 250_000 {
            return Err("单本词库最多 25 万条".into());
        }
        let bytes = imported.path.metadata().map_err(|e| e.to_string())?.len();
        if bytes > 16 * 1024 * 1024 || total + bytes > 64 * 1024 * 1024 {
            return Err("转换后的词库超过大小限制".into());
        }
        let destination = dicts.join(format!("{id}.qj"));
        std::fs::rename(imported.path, &destination).map_err(|e| e.to_string())?;
        info(&destination)
    })();
    let _ = std::fs::remove_dir_all(staging);
    result
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod metadata_tests;
