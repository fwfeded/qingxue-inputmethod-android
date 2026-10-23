//! 文件字段严格解码；业务规则仍由 Core 校验。
use qingjian_core::CustomPhrase;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct StoredPhrase {
    pub code: String,

    pub text: String,

    pub position: usize,

    pub enabled: bool,
}
impl From<CustomPhrase> for StoredPhrase {
    fn from(p: CustomPhrase) -> Self {
        Self {
            code: p.code,
            text: p.text,
            position: p.position,
            enabled: p.enabled,
        }
    }
}
impl From<StoredPhrase> for CustomPhrase {
    fn from(p: StoredPhrase) -> Self {
        Self {
            code: p.code,
            text: p.text,
            position: p.position,
            enabled: p.enabled,
        }
    }
}
