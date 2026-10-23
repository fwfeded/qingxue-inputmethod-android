//! 用随包英语等级表核对曝光、熟悉阈值、未分级词及学习恢复后的汇总。
use super::session;
use crate::session::Session;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

fn directory(label: &str) -> PathBuf {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let path = std::env::temp_dir().join(format!(
        "qingstudy-levels-{label}-{}-{stamp}",
        std::process::id()
    ));
    std::fs::create_dir_all(&path).unwrap();
    path
}

fn summary(root: &Path) -> Value {
    let active = crate::learning_backup::active_directory(root).unwrap();
    serde_json::from_str(&std::fs::read_to_string(active.join("summary.json")).unwrap()).unwrap()
}

#[test]
fn real_levels_survive_privacy_language_toggle_and_backup_restore() {
    let root = directory("records");
    std::fs::write(root.join("vocabulary.tsv"),
        "en\thello\t2\t1\t0\t2026-10-16\t2026-10-16\nen\tunlisted qingstudy test\t1\t1\t0\t2026-10-16\t2026-10-16\n").unwrap();
    let mut s = session();
    let configure = json!({"directory":root,"learning":true}).to_string();
    s.step("configure", &configure).unwrap();
    let frame = s.step("input", "nihao").unwrap();
    let i = frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|c| c["text"] == "你好")
        .unwrap();
    s.step("displayed", &json!([i]).to_string()).unwrap();
    s.step("select", &i.to_string()).unwrap();
    let initial = summary(&root);
    let levels = initial["levels"]
        .as_array()
        .expect("English level summary must be present");
    assert_eq!(
        levels
            .iter()
            .map(|l| l["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        ["A1", "A2", "B1", "B2", "C1", "C2"]
    );
    assert_eq!(
        levels
            .iter()
            .map(|l| l["total"].as_u64().unwrap())
            .sum::<u64>(),
        8845
    );
    assert_eq!(levels[0]["seen"], 2); // hello、hi；未知短语只算总词汇。
    assert_eq!(levels[0]["familiar"], 1); // hello 的第三轮曝光。
    assert_eq!(levels[0]["committed"], 2);
    assert_eq!(initial["seen"], 3);
    let before = std::fs::read(root.join("vocabulary.tsv")).unwrap();
    for settings in [
        r#"{"learning":false}"#,
        r#"{"learning":true,"private":true}"#,
    ] {
        s.step("configure", settings).unwrap();
        s.step("input", "kaifa").unwrap();
        s.step("space", "").unwrap();
        assert_eq!(summary(&root)["levels"], initial["levels"]);
        assert_eq!(std::fs::read(root.join("vocabulary.tsv")).unwrap(), before);
    }
    s.step("configure", r#"{"learning":true,"learningLanguage":"off"}"#)
        .unwrap();
    s.step("input", "nihao").unwrap();
    s.step("space", "").unwrap();
    assert_eq!(summary(&root)["levels"], json!([]));
    assert_eq!(std::fs::read(root.join("vocabulary.tsv")).unwrap(), before);
    s.step("configure", &configure).unwrap();
    s.step("flush", "").unwrap();
    assert_eq!(summary(&root)["levels"], initial["levels"]);
    let backup = root.join("backup.json");
    crate::learning_backup::export(&root, &backup).unwrap();
    s.step("input", "kaifa").unwrap();
    s.step("space", "").unwrap();
    assert_ne!(summary(&root)["levels"], initial["levels"]);
    crate::learning_backup::restore(&root, &backup).unwrap();
    s.step("configure", &configure).unwrap();
    s.step("flush", "").unwrap();
    assert_eq!(summary(&root)["levels"], initial["levels"]);
    let mut reopened = session();
    reopened.step("configure", &configure).unwrap();
    reopened.step("flush", "").unwrap();
    assert_eq!(summary(&root)["levels"], initial["levels"]);
}

#[test]
fn missing_or_broken_optional_levels_do_not_block_input_or_learning() {
    let source = session();
    let assets = directory("assets");
    for name in ["dict.tsv", "glossary-en.tsv"] {
        std::fs::copy(source.directory.join(name), assets.join(name)).unwrap();
    }
    for broken in [false, true] {
        if broken {
            std::fs::write(assets.join("levels-en.tsv"), "broken row without tab").unwrap();
        }
        let root = directory("fallback");
        let mut s = Session::open(&assets).unwrap();
        s.step(
            "configure",
            &json!({"directory":root,"learning":true}).to_string(),
        )
        .unwrap();
        s.step("input", "nihao").unwrap();
        assert_eq!(s.step("space", "").unwrap()["commit"], "你好");
        let snapshot = summary(&root);
        assert_eq!(snapshot["levels"], json!([]));
        assert!(snapshot["committed"].as_u64().unwrap() > 0);
    }
}
