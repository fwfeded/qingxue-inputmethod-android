//! 固定版本的备份封装；拒绝重复键、未知键及缺失的表。
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Document {
    pub format: String,
    pub version: u32,
    #[serde(rename = "createdUnixSeconds")]
    pub created: u64,
    pub files: super::tables::Tables,
}
