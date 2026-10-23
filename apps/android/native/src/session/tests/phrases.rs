//! 自定义短语通过安卓配置进入共享引擎，预览与提交必须分离。
use super::session;
use serde_json::json;

#[test]
fn custom_phrase_position_preview_and_exact_commit() {
    let mut s = session();
    let text = "第一行 地址：甲乙\n第二行 😀\tEND";
    s.step(
        "configure",
        &json!({"customPhrases":[{"code":"nihao","text":text,"position":2,"enabled":true}]})
            .to_string(),
    )
    .unwrap();
    let frame = s.step("input", "nihao").unwrap();
    assert_eq!(frame["candidates"][1]["text"], text);
    assert_eq!(
        frame["candidates"][1]["display"],
        qingjian_core::CustomPhrase::preview(text, 24)
    );
    assert!(!frame["candidates"][0]["gloss"].as_str().unwrap().is_empty());
    assert_eq!(s.step("digit", "1").unwrap()["commit"], text);
}

#[test]
fn phrase_validation_rejects_conflicts_and_invalid_codes() {
    for rule in [
        json!({"code":"ABC","text":"x","position":1}),
        json!({"code":"abc","text":"","position":1}),
        json!({"code":"abc","text":"x","position":10}),
    ] {
        assert!(crate::phrases::inspect(&json!([rule]).to_string()).is_err());
    }
    let rule = json!({"code":"abc","text":"x","position":1,"enabled":false});
    assert!(crate::phrases::inspect(&json!([rule, rule]).to_string()).is_err());
    assert!(
        crate::phrases::inspect(
            &json!([{"code":"a","text":"x".repeat(16385),"position":1}]).to_string()
        )
        .is_err()
    );
    assert!(
        crate::phrases::inspect(&json!([{"code":"a","text":"x","position":1}]).to_string()).is_ok()
    );
}

#[test]
fn phrases_disable_english_and_reconfigure_follow_core() {
    let mut s = session();
    let config =
        json!({"customPhrases":[{"code":"qqq","text":"原文，\n下一行","position":1}]}).to_string();
    s.step("configure", &config).unwrap();
    assert_eq!(
        s.step("input", "qqq").unwrap()["candidates"][0]["text"],
        "原文，\n下一行"
    );
    assert_eq!(s.step("space", "").unwrap()["commit"], "原文，\n下一行");
    s.step("english", "").unwrap();
    let frame = s.step("input", "qqq").unwrap();
    assert!(
        !frame["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["custom"] == true)
    );
    s.step("reset", "").unwrap();
    s.step("english", "").unwrap();
    s.step(
        "configure",
        &json!({"customPhrases":[{"code":"qqq","text":"隐藏","position":1,"enabled":false}]})
            .to_string(),
    )
    .unwrap();
    assert!(
        !s.step("input", "qqq").unwrap()["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["custom"] == true)
    );
    s.step("reset", "").unwrap();
    s.step("configure", r#"{"customPhrases":[{"bad":true}]}"#)
        .unwrap();
    assert_eq!(
        s.step("input", "nihao").unwrap()["candidates"][0]["text"],
        "你好"
    );
    s.step("reset", "").unwrap();
    let root = std::env::temp_dir().join(format!("qingstudy-phrases-{}", std::process::id()));
    let mut new_config: serde_json::Value = serde_json::from_str(&config).unwrap();
    new_config["directory"] = json!(root);
    s.step("configure", &new_config.to_string()).unwrap();
    assert_eq!(
        s.step("input", "qqq").unwrap()["candidates"][0]["text"],
        "原文，\n下一行"
    );
    drop(s);
    std::fs::remove_dir_all(root).unwrap();
}
