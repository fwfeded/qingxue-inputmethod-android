//! 八张表必须显式存在；null 表示该备份没有这张表。
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Tables {
    #[serde(rename = "frequency.tsv")]
    frequency: Value,
    #[serde(rename = "user-words.tsv")]
    words: Value,
    #[serde(rename = "user-ngram.tsv")]
    ngram: Value,
    #[serde(rename = "user-choices.tsv")]
    choices: Value,
    #[serde(rename = "user-english.tsv")]
    english: Value,
    #[serde(rename = "user-typos.tsv")]
    typos: Value,
    #[serde(rename = "vocabulary.tsv")]
    vocabulary: Value,
    #[serde(rename = "usage.tsv")]
    usage: Value,
}
