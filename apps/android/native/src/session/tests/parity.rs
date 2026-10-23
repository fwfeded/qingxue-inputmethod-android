//! 离线功能、学习隐私与真实词库实验。
use super::super::Session;
use super::session;
use std::path::Path;
fn index(frame: &serde_json::Value, text: &str) -> usize {
    frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|c| c["text"] == text)
        .unwrap_or_else(|| panic!("missing {text}: {frame}"))
}
#[test]
fn english_completion_correction_and_chinese_gloss() {
    let mut s = session();
    s.step("english", "").unwrap();
    let f = s.step("input", "recieve").unwrap();
    let i = index(&f, "receive");
    assert!(!f["candidates"][i]["gloss"].as_str().unwrap().is_empty());
    assert_eq!(
        s.step("select", &i.to_string()).unwrap()["commit"],
        "receive"
    );
    let f = s.step("input", "compa").unwrap();
    assert!(
        f["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["text"].as_str().unwrap().starts_with("compa"))
    );
}
#[test]
fn shuangpin_traditional_fuzzy_and_translation() {
    let mut s = session();
    s.step("configure", r#"{"scheme":"xiaohe","traditional":"taiwan"}"#)
        .unwrap();
    let f = s.step("input", "kdfa").unwrap();
    let i = index(&f, "開發");
    assert!(!f["candidates"][i]["gloss"].as_str().unwrap().is_empty());
    let text = f["candidates"][i]["senses"][0].clone();
    assert_eq!(s.step("translate", &i.to_string()).unwrap()["commit"], text);
    s.step("configure", r#"{"n_l":true}"#).unwrap();
    let f = s.step("input", "lihao").unwrap();
    index(&f, "你好");
}
#[test]
fn shortcuts_symbols_emoji_and_cancel() {
    let mut s = session();
    for (typed, expected) in [
        ("v1+2", "3"),
        ("v123", "一百二十三"),
        ("u4e00", "一"),
        ("ku", "😭"),
    ] {
        s.step("reset", "").unwrap();
        let f = s.step("input", typed).unwrap();
        index(&f, expected);
    }
    s.step("panel", "").unwrap();
    let f = s.step("panel-group", "1").unwrap();
    assert!(f["panel"].as_str().unwrap().contains("符号"));
    let symbol = f["candidates"][0]["text"].clone();
    assert_eq!(s.step("select", "0").unwrap()["commit"], symbol);
    assert_eq!(s.step("query", "").unwrap()["input"], "");
    let f = s.step("input", "rq").unwrap();
    assert!(
        f["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["text"].as_str().unwrap().contains('年'))
    );
    assert_eq!(s.step("cancel", "").unwrap()["input"], "");
}
#[test]
fn learning_survives_reopen_and_private_mode_does_not_write() {
    use serde_json::{Value, json};
    let mut s = session();
    let directory = std::env::temp_dir().join(format!("qingstudy-learning-{}", std::process::id()));
    let configure = json!({"directory":directory, "learning":true}).to_string();
    s.step("configure", &configure).unwrap();
    let f = s.step("input", "kaifa").unwrap();
    let i = index(&f, "开发");
    s.step("displayed", &json!([i]).to_string()).unwrap();
    s.step("select", &i.to_string()).unwrap();
    let summary: Value =
        serde_json::from_str(&std::fs::read_to_string(directory.join("summary.json")).unwrap())
            .unwrap();
    assert!(summary["total"].as_u64().unwrap() >= 2);
    assert!(summary["seen"].as_u64().unwrap() > 0);
    let data = std::fs::read(directory.join("frequency.tsv")).unwrap();
    s.step(
        "configure",
        &json!({"learning":true,"private":true}).to_string(),
    )
    .unwrap();
    s.step("input", "yingyu").unwrap();
    s.step("space", "").unwrap();
    assert_eq!(
        data,
        std::fs::read(directory.join("frequency.tsv")).unwrap()
    );
    let after: Value =
        serde_json::from_str(&std::fs::read_to_string(directory.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary, after);
    let mut reopened = Session::open(&s.directory).unwrap();
    reopened.step("configure", &configure).unwrap();
    assert!(reopened.engine.learner().weight("开发") > 0);
}

#[test]
fn real_dictionary_query_matrix_and_punctuation() {
    use serde_json::json;
    let mut s = session();
    let mut rows = Vec::new();
    for (input, expected) in [
        ("nhao", "你好"),
        ("kaif", "开发"),
        ("mingtain", "明天"),
        ("nihaoshijie", "你好世界"),
        ("hello", "hello"),
    ] {
        s.step("reset", "").unwrap();
        let f = s.step("input", input).unwrap();
        let i = index(&f, expected);
        rows.push(json!({"input":input, "expected":expected, "rank":i+1,
            "first":f["candidates"][0], "elapsedMs":f["elapsedMs"]}));
    }
    s.step("reset", "").unwrap();
    s.step("input", "nihao").unwrap();
    assert_eq!(s.step("punctuate", ",").unwrap()["commit"], "你好，");
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../output/android/evidence/parity-query-matrix.json");
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, serde_json::to_string_pretty(&rows).unwrap()).unwrap();
}

#[test]
fn queued_hardware_operators_and_unicode_stay_in_composition() {
    let mut s = session();
    s.step("input", "v").unwrap();
    s.step("digit", "0").unwrap();
    s.step("graphic", "+").unwrap();
    let f = s.step("digit", "1").unwrap();
    index(&f, "3");
    s.step("reset", "").unwrap();
    s.step("input", "u").unwrap();
    s.step("digit", "3").unwrap();
    s.step("input", "e").unwrap();
    s.step("graphic", "0").unwrap();
    let f = s.step("graphic", "0").unwrap();
    index(&f, "一");
}

#[test]
fn space_selects_first_candidate_on_requested_page() {
    let mut s = session();
    let frame = s.step("input", "shi").unwrap();
    let expected = frame["candidates"][9]["text"].clone();
    assert!(expected.is_string());
    assert_eq!(s.step("space", "9").unwrap()["commit"], expected);
}
