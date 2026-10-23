//! 安卓短语设置校验与预览，位置冲突和输入码规则复用 Core。
mod archive;
mod stored;

use qingjian_core::{CustomPhrase, custom_phrase::validate_phrases};
use serde_json::{Value, json};

pub fn parse(value: &Value) -> Result<Vec<CustomPhrase>, String> {
    if value.is_null() {
        return Ok(Vec::new());
    }
    let phrases: Vec<CustomPhrase> =
        serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
    if phrases.len() > 256 {
        return Err("最多保存 256 条短语".into());
    }
    if phrases.iter().any(|p| p.text.len() > 16 * 1024) {
        return Err("每条短语文本最多 16 KiB".into());
    }
    if value.to_string().len() > 1024 * 1024 {
        return Err("短语设置总大小不能超过 1 MiB".into());
    }
    validate_phrases(&phrases)?;
    Ok(phrases)
}

pub fn inspect(source: &str) -> Result<Value, String> {
    if source.len() > 1024 * 1024 {
        return Err("短语设置总大小不能超过 1 MiB".into());
    }
    let value: Value = serde_json::from_str(source).map_err(|e| e.to_string())?;
    let phrases = parse(&value)?;
    let previews: Vec<String> = phrases
        .iter()
        .map(|p| CustomPhrase::preview(&p.text, 24))
        .collect();
    Ok(json!({"phrases":phrases,"previews":previews}))
}

#[cfg(test)]
mod tests;

pub fn file(action: &str, source: &str) -> Result<Value, String> {
    if source.len() > 2 * 1024 * 1024 {
        return Err("短语备份文件最多 2 MiB".into());
    }
    match action {
        "export" => {
            let value: Value = serde_json::from_str(source).map_err(|e| e.to_string())?;
            if !value.is_array() {
                return Err("短语设置必须为数组".into());
            }
            let phrases = parse(&value)?;
            let count = phrases.len();
            let archive = archive::Archive {
                format: "qingstudy-phrases".into(),
                version: 1,
                phrases: phrases.into_iter().map(Into::into).collect(),
            };
            let document = serde_json::to_string(&archive).map_err(|e| e.to_string())?;
            Ok(json!({"document":document,"count":count}))
        }
        "inspect" => {
            let archive: archive::Archive =
                serde_json::from_str(source).map_err(|e| format!("备份格式无效：{e}"))?;
            if archive.format != "qingstudy-phrases" || archive.version != 1 {
                return Err("不是受支持的青学短语备份 v1".into());
            }
            let phrases: Vec<CustomPhrase> = archive.phrases.into_iter().map(Into::into).collect();
            inspect(&serde_json::to_string(&phrases).map_err(|e| e.to_string())?)
        }
        _ => Err("未知短语文件操作".into()),
    }
}
