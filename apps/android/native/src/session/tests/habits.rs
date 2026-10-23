//! 输入习惯写入独立于词汇/统计；旧总开关与私密输入仍能暂停全部记录。
use super::session;
use crate::session::Session;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

const HABIT_FILES: [&str; 6] = [
    "frequency.tsv",
    "user-words.tsv",
    "user-ngram.tsv",
    "user-choices.tsv",
    "user-english.tsv",
    "user-typos.tsv",
];

fn directory() -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "qingstudy-habits-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&path).unwrap();
    std::fs::write(path.join("frequency.tsv"), "你好\t12\n").unwrap();
    path
}

fn files(root: &Path) -> Vec<Vec<u8>> {
    HABIT_FILES
        .iter()
        .chain(["vocabulary.tsv", "usage.tsv"].iter())
        .map(|name| std::fs::read(root.join(name)).unwrap_or_default())
        .collect()
}

fn commit(s: &mut Session, input: &str, expected: &str) {
    let frame = s.step("input", input).unwrap();
    let index = frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|item| item["text"] == expected)
        .unwrap();
    s.step("displayed", &json!([index]).to_string()).unwrap();
    assert_eq!(
        s.step("select", &index.to_string()).unwrap()["commit"],
        expected
    );
}

#[test]
fn disabled_habits_keep_old_weights_but_only_add_vocabulary_and_usage() {
    let root = directory();
    let mut s = session();
    let config = json!({"directory":root,"learning":true,"habitLearning":false}).to_string();
    s.step("configure", &config).unwrap();
    let before = files(&root);
    assert_eq!(s.engine.learner().weight("你好"), 12);
    commit(&mut s, "nihao", "你好");
    commit(&mut s, "mingtain", "明天");
    s.step("english", "").unwrap();
    commit(&mut s, "recieve", "receive");
    s.step("english", "").unwrap();
    assert_eq!(s.engine.learner().weight("你好"), 12);
    let after = files(&root);
    assert_eq!(
        &after[..6],
        &before[..6],
        "six habit tables must not change"
    );
    assert_ne!(after[6], before[6]);
    assert_ne!(after[7], before[7]);
    let summary: Value =
        serde_json::from_slice(&std::fs::read(root.join("summary.json")).unwrap()).unwrap();
    assert_eq!(summary["total"], 4);
    assert_eq!(summary["english"], 1);
    assert!(summary["seen"].as_u64().unwrap() >= 2);

    // 恢复备份会装配新学习器，仍须重应用已保存的写入开关。
    let backup = root.join("backup.json");
    crate::learning_backup::export(&root, &backup).unwrap();
    crate::learning_backup::restore(&root, &backup).unwrap();
    let active = crate::learning_backup::active_directory(&root).unwrap();
    let restored = files(&active);
    let mut reopened = session();
    reopened.step("configure", &config).unwrap();
    commit(&mut reopened, "nihao", "你好");
    assert_eq!(reopened.engine.learner().weight("你好"), 12);
    assert_eq!(&files(&active)[..6], &restored[..6]);
    reopened
        .step("configure", r#"{"learning":true,"habitLearning":true}"#)
        .unwrap();
    commit(&mut reopened, "nihao", "你好");
    assert!(reopened.engine.learner().weight("你好") > 12);
}

#[test]
fn legacy_recording_switch_and_private_input_still_stop_all_eight_tables() {
    let root = directory();
    let mut s = session();
    s.step(
        "configure",
        &json!({"directory":root,"learning":true}).to_string(),
    )
    .unwrap();
    commit(&mut s, "nihao", "你好");
    assert!(s.engine.learner().weight("你好") > 12); // 缺少新键时保持旧行为。
    let before = files(&root);
    for settings in [
        json!({"learning":false,"habitLearning":true}),
        json!({"learning":true,"habitLearning":true,"private":true}),
        json!({"habitLearning":true}),
    ] {
        s.step("configure", &settings.to_string()).unwrap();
        commit(&mut s, "kaifa", "开发");
        assert_eq!(files(&root), before);
    }
}
