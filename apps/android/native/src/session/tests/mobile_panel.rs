//! 手机直接入口、连续符号选择及分类数据回归。
use super::session;

#[test]
fn mobile_panel_preserves_category_after_commit_and_punctuation_is_not_a_selection() {
    let mut session = session();
    session.step("input", "nihao").unwrap();
    let opened = session.step("panel-open", "符号").unwrap();
    assert_eq!(opened["commit"], "你好");
    assert_eq!(opened["input"], "");
    assert_eq!(opened["panelGroup"], "符号");
    assert!(opened["panelSections"].as_array().unwrap().len() > 2);
    let category = session.step("panel-category", "1").unwrap();
    let text = category["candidates"][0]["text"].clone();
    let selected = session.step("panel-select", "0").unwrap();
    assert_eq!(selected["commit"], text);
    assert_eq!(selected["panel"], category["panel"]);
    assert_eq!(session.step("panel-select", "0").unwrap()["commit"], text);
    assert_eq!(session.step("punctuate", ",").unwrap()["commit"], "，");
    let emoji = session.step("panel-open", "表情").unwrap();
    assert_eq!(emoji["panelGroup"], "表情");
    assert!(!emoji["candidates"].as_array().unwrap().is_empty());
    assert!(session.step("panel-select", "99999").is_err());
    assert!(session.step("panel-close", "").unwrap()["panel"].is_null());
}
