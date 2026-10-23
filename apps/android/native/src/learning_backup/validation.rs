//! 恢复前严格校验行结构与计数；不让上游宽容读取静默丢弃备份行。
use serde_json::{Value, json};
use std::collections::HashSet;
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub(super) fn read(source: &Path) -> Result<(Value, Value), String> {
    let mut bytes = Vec::new();
    File::open(source)
        .map_err(|e| e.to_string())?
        .take(32 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > 32 * 1024 * 1024 {
        return Err("备份文件超过 32 MB".into());
    }
    let document: super::document::Document =
        serde_json::from_slice(&bytes).map_err(|e| format!("备份格式错误：{e}"))?;
    if document.format != "qingstudy-learning-backup" || document.version != 1 {
        return Err("不支持此学习备份格式或版本".into());
    }
    let files = serde_json::to_value(document.files).map_err(|e| e.to_string())?;
    let (mut count, mut total, mut rows) = (0, 0, 0);
    for name in super::TABLES {
        let value = &files[name];
        if value.is_null() {
            continue;
        }
        let text = value
            .as_str()
            .ok_or_else(|| format!("学习表 {name} 必须是文本或 null"))?;
        total += text.len();
        if text.len() as u64 > super::MAX_FILE || total > super::MAX_TOTAL {
            return Err("学习备份超过限制：单表 4 MB、合计 16 MB".into());
        }
        rows += validate_table(name, text)?;
        count += 1;
    }
    Ok((files, json!({"files":count,"bytes":total,"rows":rows})))
}

fn date(text: &str) -> bool {
    text.parse::<jiff::civil::Date>().is_ok()
}

fn validate_table(name: &str, text: &str) -> Result<usize, String> {
    let mut keys = HashSet::new();
    let mut count_sum = 0u64;
    let mut usage_sums = [0u64; 4];
    for (index, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        let fail = || format!("学习表 {name} 第 {} 行无效或重复", index + 1);
        if raw.starts_with('\t')
            || fields
                .iter()
                .any(|f| f.trim().is_empty() || f.chars().any(char::is_control))
        {
            return Err(fail());
        }
        let len = fields.len();
        let valid = match name {
            "frequency.tsv" | "user-english.tsv" => len == 2,
            "user-words.tsv" | "user-choices.tsv" | "user-typos.tsv" => len == 3,
            "user-ngram.tsv" => len == 3 || len == 4,
            "vocabulary.tsv" => {
                len == 7 && date(fields[5]) && date(fields[6]) && fields[5] <= fields[6]
            }
            "usage.tsv" => len == 5 && date(fields[0]),
            _ => false,
        };
        if !valid {
            return Err(fail());
        }
        if name == "user-words.tsv"
            && !fields[1]
                .split_whitespace()
                .all(qingjian_core::parser::is_syllable)
        {
            return Err(fail());
        }
        let key = match name {
            "user-english.tsv" => fields[0].to_ascii_lowercase(),
            "vocabulary.tsv" | "user-choices.tsv" | "user-typos.tsv" => fields[..2].join("\t"),
            "user-ngram.tsv" => fields[..len - 1].join("\t"),
            _ => fields[0].to_owned(),
        };
        if !keys.insert(key) {
            return Err(fail());
        }
        if name == "usage.tsv" {
            for (sum, field) in usage_sums.iter_mut().zip(&fields[1..]) {
                let number = field.parse::<u64>().map_err(|_| fail())?;
                *sum = sum
                    .checked_add(number)
                    .filter(|s| *s < u64::MAX / 2)
                    .ok_or_else(fail)?;
            }
        } else if name == "vocabulary.tsv" {
            for field in &fields[2..5] {
                field
                    .parse::<u32>()
                    .ok()
                    .filter(|n| *n < u32::MAX / 2)
                    .ok_or_else(fail)?;
            }
        } else {
            let number = fields[len - 1]
                .parse::<u32>()
                .ok()
                .filter(|n| *n < u32::MAX / 2)
                .ok_or_else(fail)?;
            count_sum += u64::from(number);
            // 上游 n-gram 汇总用 u32；保留继续学习的余量。
            if name == "user-ngram.tsv" && count_sum >= u64::from(u32::MAX / 2) {
                return Err(fail());
            }
        }
    }
    if name == "user-words.tsv" && !keys.is_empty() {
        qingjian_dictionary::Dictionary::parse(text).map_err(|e| format!("用户词拼音无效：{e}"))?;
    }
    Ok(keys.len())
}
