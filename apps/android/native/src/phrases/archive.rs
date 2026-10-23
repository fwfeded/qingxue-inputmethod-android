//! 安卓短语备份 v1；拒绝错版本及未知/重复字段，避免静默丢失。
use super::stored::StoredPhrase;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Archive {
    pub format: String,

    pub version: u32,

    pub phrases: Vec<StoredPhrase>,
}
