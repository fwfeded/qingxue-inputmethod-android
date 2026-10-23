//! 释义开关独立于输入学习；切换不残留译词与曝光，不删除既有词汇记录。
use super::session;
use serde_json::{Value, json};

fn word(frame: &Value, text: &str) -> usize {
    frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|item| item["text"] == text)
        .unwrap()
}

#[test]
fn translation_off_on_preserves_input_and_reverse_glosses() {
    let mut s = session();
    let initial = s.step("input", "nihao").unwrap();
    let i = word(&initial, "你好");
    assert!(
        !initial["candidates"][i]["senses"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    let off = s
        .step("configure", r#"{"learningLanguage":"off"}"#)
        .unwrap();
    let i = word(&off, "你好");
    assert_eq!(off["input"], "nihao");
    assert_eq!(off["candidates"][i]["senses"], json!([]));
    assert_eq!(off["candidates"][i]["fresh"], false);
    assert_eq!(s.step("translate", &i.to_string()).unwrap()["commit"], "");
    assert_eq!(s.step("space", "").unwrap()["commit"], "你好");
    s.step("english", "").unwrap();
    let reverse = s.step("input", "recieve").unwrap();
    let i = word(&reverse, "receive");
    assert!(
        !reverse["candidates"][i]["senses"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        s.step("select", &i.to_string()).unwrap()["commit"],
        "receive"
    );
    s.step("english", "").unwrap();
    for settings in [
        r#"{"learningLanguage":"en"}"#,
        r#"{}"#,
        r#"{"learningLanguage":"unknown"}"#,
    ] {
        s.step("configure", r#"{"learningLanguage":"off"}"#)
            .unwrap();
        s.step("configure", settings).unwrap();
        let restored = s.step("input", "nihao").unwrap();
        let i = word(&restored, "你好");
        assert_eq!(
            restored["candidates"][i]["senses"],
            initial["candidates"][word(&initial, "你好")]["senses"]
        );
        s.step("space", "").unwrap();
    }
}

#[test]
fn disabling_gloss_clears_pending_exposure_but_keeps_frequency_and_vocabulary() {
    let mut s = session();
    let directory = std::env::temp_dir().join(format!(
        "qingstudy-language-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    s.step(
        "configure",
        &json!({"directory":directory,"learning":true}).to_string(),
    )
    .unwrap();
    let frame = s.step("input", "kaifa").unwrap();
    let i = word(&frame, "开发");
    s.step("displayed", &json!([i]).to_string()).unwrap();
    s.step("select", &i.to_string()).unwrap();
    let vocabulary = std::fs::read(directory.join("vocabulary.tsv")).unwrap();
    let frame = s.step("input", "nihao").unwrap();
    s.step("displayed", &json!([word(&frame, "你好")]).to_string())
        .unwrap();
    s.step(
        "configure",
        &json!({"learning":true,"learningLanguage":"off"}).to_string(),
    )
    .unwrap();
    assert_eq!(s.step("space", "").unwrap()["commit"], "你好");
    assert!(s.engine.learner().weight("你好") > 0);
    assert_eq!(
        vocabulary,
        std::fs::read(directory.join("vocabulary.tsv")).unwrap()
    );
    let usage: Value =
        serde_json::from_str(&std::fs::read_to_string(directory.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(usage["total"], 4);
    // 学习目录重装配会建立默认英语译者，仍须应用已保存的关闭选项。
    let mut reopened = session();
    reopened
        .step(
            "configure",
            &json!({"directory":directory,"learning":true,"learningLanguage":"off"}).to_string(),
        )
        .unwrap();
    let frame = reopened.step("input", "nihao").unwrap();
    assert_eq!(
        frame["candidates"][word(&frame, "你好")]["senses"],
        json!([])
    );
    reopened
        .step("configure", r#"{"learning":true,"learningLanguage":"en"}"#)
        .unwrap();
    reopened.step("flush", "").unwrap();
    assert_eq!(
        vocabulary,
        std::fs::read(directory.join("vocabulary.tsv")).unwrap()
    );
    let summary: Value =
        serde_json::from_str(&std::fs::read_to_string(directory.join("summary.json")).unwrap())
            .unwrap();
    assert!(summary["seen"].as_u64().unwrap() > 0);
}
