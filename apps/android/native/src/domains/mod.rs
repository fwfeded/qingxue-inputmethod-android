//! 随包领域词库目录与选择装配；候选与排序仍由共享 Dictionary / Engine 处理。
use qingjian_dictionary::Dictionary;
use serde_json::{Value, json};
use std::path::Path;

const CATALOG: [(&str, &str); 11] = [
    ("idioms", "成语"),
    ("it_computing", "IT 与计算机"),
    ("medicine", "医学"),
    ("law", "法律"),
    ("places", "地名"),
    ("poetry_lines", "诗词名句"),
    ("finance", "财经"),
    ("food", "饮食"),
    ("animals", "动物"),
    ("automotive", "汽车"),
    ("historical_figures", "历史人物"),
];

pub fn selected(settings: &Value) -> Vec<&'static str> {
    CATALOG
        .iter()
        .map(|(id, _)| *id)
        .filter(|id| {
            settings["domains"]
                .as_array()
                .map_or(*id == "idioms", |items| {
                    items.iter().any(|value| value.as_str() == Some(*id))
                })
        })
        .collect()
}

pub fn signature(directory: &Path, ids: &[&str]) -> String {
    ids.iter()
        .map(|id| {
            let metadata = std::fs::metadata(directory.join(format!("{id}.tsv"))).ok();
            format!(
                "{id}:{:?}:{:?}",
                metadata.as_ref().map(|m| m.len()),
                metadata.and_then(|m| m.modified().ok())
            )
        })
        .collect::<Vec<_>>()
        .join("|")
}

pub fn load(directory: &Path, ids: &[&str]) -> Vec<Dictionary> {
    // 随包文件缺失或损坏时仍允许基础输入；设置页显示各文件的可用状态。
    ids.iter()
        .filter_map(|id| Dictionary::from_path(directory.join(format!("{id}.tsv"))).ok())
        .collect()
}

pub fn info(directory: &Path) -> Value {
    Value::Array(
        CATALOG
            .iter()
            .map(
                |(id, name)| match Dictionary::from_path(directory.join(format!("{id}.tsv"))) {
                    Ok(dictionary) => {
                        json!({"id":id,"name":name,"entries":dictionary.len(),"available":true,
                "license":"MIT","source":"THUOCL","defaultEnabled":*id=="idioms"})
                    }
                    Err(error) => {
                        json!({"id":id,"name":name,"available":false,"error":error.to_string(),
                "license":"MIT","source":"THUOCL","defaultEnabled":*id=="idioms"})
                    }
                },
            )
            .collect(),
    )
}
