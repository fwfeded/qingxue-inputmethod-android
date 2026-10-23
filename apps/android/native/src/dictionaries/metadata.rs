//! 恢复安卓 TSV 备份头；词条解析与 QJ 写入仍由共享 Dictionary 完成。
use qingjian_dictionary::Dictionary;
use qingjian_format::Metadata;
use serde_json::Value;
use std::path::{Path, PathBuf};

pub(super) fn restore(source: &Path, converted: &Path) -> Result<PathBuf, String> {
    let Ok(text) = std::fs::read_to_string(source) else {
        return Ok(converted.to_owned());
    };
    let text = text.strip_prefix('\u{feff}').unwrap_or(&text);
    let first = text.lines().next().unwrap_or("").trim();
    let payload = if let Some(payload) = first.strip_prefix("# qingjian-dictionary-metadata-v1:") {
        payload.trim()
    } else if first.starts_with("# qingjian-dictionary-metadata-") {
        return Err("不支持此词库备份版本，请使用更新的应用导入".into());
    } else if let Some(payload) = first.strip_prefix("# ") {
        // 0.3.0 的头没有格式标记；只识别其完整字段组合，普通 JSON 注释不算备份声明。
        let legacy: Value = serde_json::from_str(payload).unwrap_or(Value::Null);
        if ![
            "name",
            "license",
            "attribution",
            "source",
            "version",
            "entries",
            "generator",
        ]
        .iter()
        .all(|key| legacy.get(key).is_some())
        {
            return Ok(converted.to_owned());
        }
        payload
    } else {
        return Ok(converted.to_owned());
    };
    let dictionary = Dictionary::from_path(converted).map_err(|e| e.to_string())?;
    let metadata: Metadata =
        serde_json::from_str(payload).map_err(|_| "词库备份信息损坏，请检查文件头")?;
    if metadata.name.trim().is_empty() {
        return Err("词库备份缺少名称".into());
    }
    // 新文件与正在 mmap 的 converted 分开，兼容 Windows；失败仍留在暂存目录内。
    let restored = converted.with_extension("restored.qj");
    dictionary
        .write_qj(&restored, &metadata)
        .map_err(|e| e.to_string())?;
    Ok(restored)
}
